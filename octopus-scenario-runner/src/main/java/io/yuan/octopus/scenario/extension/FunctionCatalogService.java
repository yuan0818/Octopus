package io.yuan.octopus.scenario.extension;

import io.yuan.octopus.extension.runtime.BuiltinFunctions;
import io.yuan.octopus.extension.runtime.ExtensionFailure;
import io.yuan.octopus.extension.runtime.ExtensionInvoker;
import io.yuan.octopus.preparation.PreparationRuntime;
import io.yuan.octopus.preparation.PreparationTools;
import io.yuan.octopus.scenario.extension.ExtensionModel.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds the unified catalog (FR-FN-01) and serves discovery and trial calls (FR-FN-05/20/25). Disabled
 * extensions are left out of the catalog so new derivations cannot select them; already-confirmed flows keep
 * running on their pinned versions (FR-EXT-27).
 */
@Service
public class FunctionCatalogService {
    public static final int MAX_TRY_ARGUMENT_CHARS = 10_000;

    private record Meta(String determinism, List<String> stages, String whenToUse, String whenNotToUse) {}

    private static final Map<String, Meta> PLATFORM = Map.of(
            "time.now", new Meta("CLOCK", List.of("REQUEST_VALUE"), "需要当前 Unix 毫秒时间戳，并在同一请求的多个位置复用同一次结果", "预期值"),
            "uuid", new Meta("RANDOM", List.of("REQUEST_VALUE"), "需要唯一的请求 ID 或单号", "预期值；需要可复现的值"),
            "json.object", new Meta("PURE", List.of("REQUEST_VALUE", "REQUEST_TRANSFORM"), "把多个独立绑定的字段组装成请求体对象", ""),
            "string.concat", new Meta("PURE", List.of("REQUEST_VALUE", "EXPECTED_VALUE"), "拼接前缀与值，例如 Bearer 前缀", ""),
            "digest", new Meta("PURE", List.of("REQUEST_VALUE", "EXPECTED_VALUE"), "计算 MD5 / SHA-256 摘要", "加密或 HMAC 签名"),
            "platform.encrypt", new Meta("RANDOM", List.of("REQUEST_TRANSFORM"), "Platform 网关要求的请求加密", "非 Platform 网关接口"),
            "platform.sign", new Meta("PURE", List.of("REQUEST_TRANSFORM"), "Platform 网关签名", "非 Platform 网关接口"),
            "string.value", new Meta("PURE", List.of("REQUEST_VALUE", "EXPECTED_VALUE"), "把时间戳等标量转换为请求头文本", ""));

    private record Doc(String title, String summary, String whenToUse, String whenNotToUse, List<String> paramDocs) {}

    private static final Map<String, Doc> BUILTIN = Map.ofEntries(
            Map.entry("jm.time", new Doc("当前时间", "按格式返回当前时间；格式为空时返回 Unix 毫秒", "需要格式化的当前时间", "预期值",
                    List.of("SimpleDateFormat 格式，或 YMD、HMS、YMDHMS 等简写；为空返回毫秒"))),
            Map.entry("jm.uuid", new Doc("随机 UUID", "返回随机 UUID", "需要唯一 ID", "预期值", List.of())),
            Map.entry("jm.digest", new Doc("摘要", "按算法计算文本摘要（可加盐），输出十六进制", "需要 MD5 / SHA-1 / SHA-256 / SHA-512 摘要",
                    "HMAC 或加密", List.of("算法，如 SHA-256", "待摘要文本", "盐，可选", "是否输出大写，可选"))),
            Map.entry("jm.timeShift", new Doc("时间偏移", "在给定时间（为空则当前时间）上按 ISO-8601 时长偏移后格式化", "需要相对当前时间的日期，如明天",
                    "预期值", List.of("输出格式，为空返回毫秒", "基准时间，为空表示当前", "ISO-8601 偏移，如 P1D、-PT30M"))),
            Map.entry("jm.dateTimeConvert", new Doc("日期格式转换", "把日期从源格式转换为目标格式", "上游返回的日期需要换格式后再使用", "",
                    List.of("日期文本", "源格式；为空表示毫秒", "目标格式"))),
            Map.entry("jm.randomString", new Doc("随机字符串", "生成指定长度的随机字符串", "需要随机但满足长度的输入", "预期值",
                    List.of("长度", "可选字符集，为空表示字母数字"))),
            Map.entry("jm.random", new Doc("随机整数", "返回 [min, max] 区间内的随机整数", "需要随机数值输入", "预期值", List.of("最小值", "最大值"))),
            Map.entry("jm.urlencode", new Doc("URL 编码", "按 application/x-www-form-urlencoded 编码文本", "需要把值放入 URL 或表单", "", List.of("待编码文本"))),
            Map.entry("jm.urldecode", new Doc("URL 解码", "解码 URL 编码的文本", "上游返回 URL 编码的值", "", List.of("待解码文本"))),
            Map.entry("jm.changeCase", new Doc("大小写转换", "按模式转换大小写：UPPER、LOWER、CAPITALIZE", "接口要求固定大小写", "", List.of("文本", "UPPER / LOWER / CAPITALIZE"))),
            Map.entry("jm.char", new Doc("字符", "把字符编码转换为字符", "需要不可直接输入的字符", "", List.of("十进制或 0x 十六进制字符编码"))));

    private final ExtensionService extensions;
    private final Path storage;
    private final Path jmeterHome;

    public FunctionCatalogService(ExtensionService extensions,
                                  @Value("${scenario.extensions.storage-dir:data/jmeter-extensions}") String storage,
                                  @Value("${scenario.jmeter.home:}") String jmeterHome) {
        this.extensions = extensions;
        this.storage = Path.of(storage).toAbsolutePath();
        this.jmeterHome = jmeterHome == null || jmeterHome.isBlank() ? null : Path.of(jmeterHome).toAbsolutePath();
    }

    public FunctionCatalog catalog() {
        var entries = new ArrayList<FunctionCatalog.Entry>();
        for (var tool : PreparationTools.ALL) entries.add(platform(tool));
        for (var builtin : BuiltinFunctions.all()) entries.add(builtin(builtin));
        for (var published : extensions.active())
            entries.add(FunctionCatalog.fromTool(published.tool(), published.deprecated(),
                    published.warnings().stream().map(i -> i.message).toList()));
        return new FunctionCatalog(entries);
    }

    public List<FunctionCatalog.Brief> search(String query, String stage, Integer limit) {
        return catalog().search(query, stage, limit == null ? FunctionCatalog.DEFAULT_SEARCH_LIMIT : limit);
    }

    public Map<String, Object> describe(String name) {
        return FunctionCatalog.describe(entry(name));
    }

    public String markdown(String name) {
        return FunctionCatalog.markdown(entry(name));
    }

    public String markdown(Tool tool, List<Issue> issues, String deprecated) {
        var warnings = issues.stream().filter(i -> !i.error() && (i.className == null || i.className.equals(tool.className))).map(i -> i.message).toList();
        return FunctionCatalog.markdown(FunctionCatalog.fromTool(tool, deprecated, warnings));
    }

    public FunctionCatalog.Entry entry(String name) {
        return catalog().find(name).orElseThrow(() -> new IllegalArgumentException("目录中没有 " + name + "（可能未发布或已停用）"));
    }

    /**
     * FR-FN-25: executes a PURE entry with literal, non-sensitive arguments. Platform tools and JMeter built-ins
     * run in-process (platform code); uploaded extensions run in the isolated verification process.
     */
    public Map<String, Object> tryCall(String name, Map<String, Object> arguments) {
        var entry = entry(name);
        require("PURE".equals(entry.determinism), "只能试算 PURE 条目（时间或随机结果不可复现）");
        var args = arguments == null ? Map.<String, Object>of() : arguments;
        for (var p : entry.params) require(!p.sensitive || !args.containsKey(p.name), "试算不能使用敏感参数: " + p.name);
        require(entry.params.stream().noneMatch(p -> p.sensitive && p.required), "该条目需要敏感参数，不能试算");
        require(io.yuan.octopus.extension.sdk.Json.write(args).length() <= MAX_TRY_ARGUMENT_CHARS, "试算参数过长");
        try {
            switch (entry.kind) {
                case "PLATFORM" -> {
                    return Map.of("outputs", PreparationRuntime.execute(entry.id, entry.version, args));
                }
                case "JMETER" -> {
                    var builtin = BuiltinFunctions.find(entry.id, entry.version).orElseThrow();
                    return Map.of("outputs", Map.of("value", ExtensionInvoker.invoke(builtin, args)));
                }
                default -> {
                    var published = extensions.active().stream().filter(p -> p.tool().key().equals(entry.key())).findFirst().orElseThrow();
                    var tool = published.tool();
                    var raw = new ArrayList<String>();
                    for (var p : tool.params) raw.add(ExtensionInvoker.text(args.get(p.name), p.type));
                    for (String key : args.keySet()) require(tool.params.stream().anyMatch(p -> p.name.equals(key)), "未知参数: " + key);
                    while (!raw.isEmpty() && raw.getLast().isEmpty() && !tool.params.get(raw.size() - 1).required) raw.removeLast();
                    require(jmeterHome != null && ExtensionVerifier.probeInstalled(jmeterHome), "未配置 JMETER_HOME，无法在隔离环境中试算扩展");
                    var verifier = new ExtensionVerifier(Path.of(System.getProperty("java.home"), "bin", "java"), jmeterHome,
                            ExtensionVerifier.classpathFor(jmeterHome));
                    var trial = verifier.trial(Files.readAllBytes(storage.resolve(published.sha256() + ".jar")), tool, raw);
                    if (trial.errorCode != null) return Map.of("error", trial.errorCode);
                    return Map.of("outputs", Map.of("value", ExtensionInvoker.output(trial.output, tool.output.type)));
                }
            }
        } catch (PreparationRuntime.Failure e) {
            return Map.of("error", e.code);
        } catch (ExtensionFailure e) {
            return Map.of("error", e.code);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("扩展存储不可读取");
        }
    }

    private static FunctionCatalog.Entry platform(PreparationTools.Tool tool) {
        var meta = PLATFORM.getOrDefault(tool.id(), new Meta("PURE", List.of("REQUEST_VALUE"), "", ""));
        var e = new FunctionCatalog.Entry();
        e.id = tool.id();
        e.version = tool.version();
        e.kind = "PLATFORM";
        e.title = tool.title();
        e.summary = tool.description();
        e.whenToUse = meta.whenToUse();
        e.whenNotToUse = meta.whenNotToUse();
        e.category = "platform";
        e.determinism = meta.determinism();
        e.stages = meta.stages();
        int index = 0;
        for (var name : new TreeSet<>(tool.inputs().keySet())) {
            var p = new Param();
            p.index = index++;
            p.name = name;
            p.type = tool.inputs().get(name).toUpperCase(Locale.ROOT);
            p.description = name;
            p.sensitive = tool.sensitiveInputs().contains(name);
            e.params.add(p);
        }
        for (var name : new TreeSet<>(tool.outputs().keySet())) {
            var o = new Output();
            o.type = tool.outputs().get(name).toUpperCase(Locale.ROOT);
            o.description = name;
            e.outputs.put(name, o);
        }
        return e;
    }

    private static FunctionCatalog.Entry builtin(io.yuan.octopus.extension.runtime.ExtensionEntry builtin) {
        var doc = BUILTIN.get(builtin.id);
        var e = new FunctionCatalog.Entry();
        e.id = builtin.id;
        e.version = builtin.version;
        e.kind = "JMETER";
        e.title = doc == null ? builtin.referenceKey : doc.title();
        e.summary = doc == null ? builtin.referenceKey : doc.summary();
        e.description = "JMeter 内置函数 " + builtin.referenceKey + "（不暴露写入变量的参数）";
        e.whenToUse = doc == null ? "" : doc.whenToUse();
        e.whenNotToUse = doc == null ? "" : doc.whenNotToUse();
        e.category = "jmeter";
        e.determinism = builtin.determinism;
        e.stages = builtin.stages;
        for (int i = 0; i < builtin.params.size(); i++) {
            var source = builtin.params.get(i);
            var p = new Param();
            p.index = i;
            p.name = source.name;
            p.type = source.type;
            p.required = source.required;
            p.description = doc != null && i < doc.paramDocs().size() ? doc.paramDocs().get(i) : source.name;
            e.params.add(p);
        }
        var o = new Output();
        o.type = builtin.outputType;
        o.description = "函数结果";
        e.outputs.put("value", o);
        return e;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
