package io.yuan.octopus.preparation;

import java.util.*;
import static io.yuan.octopus.preparation.PreparationPlan.*;

/** The same closed catalog is used by model planning, validation, compilation and execution. */
public final class PreparationTools {
    public record Tool(String id, String version, String title, Map<String,String> inputs,
                       Map<String,String> outputs, List<String> sensitiveInputs, String description) {}
    public static final List<Tool> ALL = List.of(
        tool("time.now", "生成时间戳", Map.of(), Map.of("value","integer"), List.of(), "Unix 毫秒；同一请求通过 result 引用同一次结果"),
        tool("uuid", "生成请求 ID", Map.of(), Map.of("value","string"), List.of(), "随机 UUID"),
        tool("json.object", "组装业务数据", Map.of(), Map.of("value","object"), List.of(), "inputs 的每个键成为对象字段，每个值独立绑定；最多 50 个字段"),
        tool("string.concat", "拼接请求字段", Map.of("prefix","string","value","string"), Map.of("value","string"), List.of(), "例如 Bearer 前缀与上游 token"),
        tool("digest", "计算摘要", Map.of("algorithm","string","text","string"), Map.of("value","string"), List.of(), "MD5 或 SHA-256，UTF-8，小写十六进制；不是加密或 HMAC"),
        tool("platform.encrypt", "Platform 请求加密", Map.of("content","object","publicKey","string","keyPairId","string","timestamp","integer","version","integer"), Map.of("body","object"), List.of("publicKey"), "v1: 每次安全生成 16 位 ASCII AES key；AES/ECB/PKCS5Padding + RSA/ECB/PKCS1Padding 公钥加密，Base64；输出 cipher/data/keyPairId/timestamp/version，不发送明文 content"),
        tool("platform.sign", "Platform 网关签名", Map.of("body","object","query","object","appid","string","requestid","string","timestamp","string","algorithm","string","signingSecret","string"), Map.of("sign","string"), List.of("signingSecret"), "v1: header requestid/appid/timestamp → Query → 最终 Body 依次合并，TreeMap 排序，拼接 key=value&，末尾 key=secret；MD5 或 SHA-256；按源码的嵌套对象规则序列化"),
        tool("string.value", "转换为文本", Map.of("value","any"), Map.of("value","string"), List.of(), "将时间戳等标量转换为请求头文本")
    );
    private static Tool tool(String id,String title,Map<String,String> in,Map<String,String> out,List<String> sensitive,String description) {
        return new Tool(id,"1",title,in,out,sensitive,description);
    }
    public static Tool get(String id) {
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("不支持的请求预处理工具"));
    }
    /** Built-in tool or published extension version ({@code x.<name>}); extensions coexist by version. */
    public static Tool get(String id, String version) {
        if(io.yuan.octopus.extension.runtime.ExtensionTools.isExtension(id))
            return io.yuan.octopus.extension.runtime.ExtensionTools.find(id,version).map(PreparationTools::extensionTool)
                    .orElseThrow(() -> new IllegalArgumentException("扩展未发布或已停用: "+id+"@"+version));
        var tool=get(id); require(tool.version().equals(version),"请求预处理工具版本不支持"); return tool;
    }
    /** Extension parameters keep their declared order; the single output is always {@code value}. */
    public static Tool extensionTool(io.yuan.octopus.extension.runtime.ExtensionEntry entry) {
        var inputs=new LinkedHashMap<String,String>(); var sensitive=new ArrayList<String>();
        entry.params.forEach(p -> { inputs.put(p.name,p.type.toLowerCase(Locale.ROOT)); if(p.sensitive) sensitive.add(p.name); });
        return new Tool(entry.id,entry.version,entry.name,inputs,Map.of("value",entry.outputType.toLowerCase(Locale.ROOT)),List.copyOf(sensitive),"JMeter 扩展 "+entry.name+"@"+entry.version);
    }
    public static List<Value> values(PreparationPlan p) {
        if(p==null) return List.of();
        var result=new ArrayList<Value>(); p.tools().forEach(c -> result.addAll(c.inputs().values()));
        if(p.body()!=null) result.add(p.body()); result.addAll(p.headers().values()); return result;
    }
    public static void validate(PreparationPlan p) {
        if(p==null) return;
        require(p.tools()!=null && !p.tools().isEmpty() && p.tools().size()<=32 && p.headers()!=null && p.headers().size()<=30,"请求预处理计划不完整或过长");
        Set<String> ids=new HashSet<>();
        for(var call:p.tools()) {
            require(call!=null && call.id()!=null && call.id().matches("[A-Za-z][A-Za-z0-9_-]{0,59}") && !call.id().equals("request") && ids.add(call.id()),"预处理动作 ID 无效或重复");
            var tool=get(call.tool(),call.version());
            require(call.inputs()!=null && call.inputs().size()<=50,"预处理参数无效");
            if(io.yuan.octopus.extension.runtime.ExtensionTools.isExtension(call.tool())) {
                var entry=io.yuan.octopus.extension.runtime.ExtensionTools.find(call.tool(),call.version()).orElseThrow();
                require(tool.inputs().keySet().containsAll(call.inputs().keySet()),"扩展参数未知");
                entry.params.stream().filter(param -> param.required).forEach(param -> require(call.inputs().containsKey(param.name),"扩展必填参数缺失: "+param.name));
            } else require(tool.id().equals("json.object") || call.inputs().keySet().equals(tool.inputs().keySet()),"预处理工具参数缺失或未知");
            call.inputs().forEach((key,v) -> {
                require(key.matches("[A-Za-z_][A-Za-z0-9_.-]{0,119}"),"预处理字段名无效"); value(v);
                require(!"literal".equals(v.source()) || !tool.sensitiveInputs().contains(key) && !sensitive(key) && !sensitiveValue(v.value()),"预处理敏感字段必须引用运行时凭据或上下文");
            });
        }
        if(p.body()!=null) value(p.body());
        p.headers().forEach((key,v) -> {
            require(key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") && !Set.of("host","content-length","transfer-encoding","connection","content-type").contains(key.toLowerCase(Locale.ROOT)),"预处理请求头无效或属于传输控制字段");
            value(v); require(!sensitive(key) || !"literal".equals(v.source()),"敏感请求头不能保存字面量");
        });
        require(p.headers().keySet().stream().map(k->k.toLowerCase(Locale.ROOT)).distinct().count()==p.headers().size(),"预处理请求头重复");
    }
    private static void value(Value v) {
        require(v!=null && v.source()!=null && Set.of("literal","context","secret","result","input","request").contains(v.source()) && v.value()!=null,"预处理绑定无效");
        if(!"literal".equals(v.source())) require(v.value() instanceof String s && s.matches("[A-Za-z_][A-Za-z0-9_.-]{0,119}"),"预处理引用无效");
    }
    public static boolean sensitive(String key) { return key.toLowerCase(Locale.ROOT).matches(".*(password|passwd|secret|token|authorization|cookie|privatekey|publickey|credential).*" ); }
    private static boolean sensitiveValue(Object v) {
        if(v instanceof Map<?,?> m) return m.entrySet().stream().anyMatch(e -> sensitive(String.valueOf(e.getKey())) || sensitiveValue(e.getValue()));
        return v instanceof List<?> l && l.stream().anyMatch(PreparationTools::sensitiveValue);
    }
    public static void require(boolean ok,String message) { if(!ok) throw new IllegalArgumentException(message); }
}
