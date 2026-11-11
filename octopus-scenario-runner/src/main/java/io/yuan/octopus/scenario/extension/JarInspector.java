package io.yuan.octopus.scenario.extension;

import io.yuan.octopus.scenario.extension.ExtensionModel.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.asm.*;

/**
 * Static inspection of an uploaded extension JAR (FR-EXT-04, FR-EXT-21–23). Reads bytecode and annotations
 * with ASM; no uploaded class is loaded, initialised or executed in the platform process.
 *
 * <p>Produces the parsed tool metadata plus ERROR issues (block publication) and WARNING issues (shown to
 * users and in the tool documentation).
 */
public final class JarInspector {
    public static final long MAX_JAR_BYTES = 20L * 1024 * 1024;
    public static final long MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024;
    public static final int MAX_ENTRIES = 5_000;
    /** Java 21 class files; the runner JVM is 21. */
    public static final int MAX_CLASS_MAJOR = 65;
    public static final int MAX_TOOL_DEFINITION_CHARS = 6_000;

    static final String SDK = "io/yuan/octopus/extension/sdk/";
    static final String FUNCTION = "org/apache/jmeter/functions/Function";
    static final String ABSTRACT_FUNCTION = "org/apache/jmeter/functions/AbstractFunction";
    static final String OCTOPUS_FUNCTION = SDK + "OctopusFunction";
    private static final List<String> RESERVED = List.of("org/apache/jmeter/", "org/apache/jorphan/", "io/yuan/octopus/",
            "java/", "javax/", "jdk/", "sun/", "com/sun/");
    private static final Pattern CATEGORY = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Set<String> TYPES = Set.of("STRING", "INTEGER", "NUMBER", "BOOLEAN", "OBJECT");

    private static final class Ann {
        final String desc;
        final Map<String, Object> values = new LinkedHashMap<>();
        Ann(String desc) { this.desc = desc; }
    }

    private static final class ClassInfo {
        String name;
        String superName;
        List<String> interfaces = List.of();
        int access;
        int major;
        boolean publicNoArgConstructor;
        final List<Ann> annotations = new ArrayList<>();
        final Set<String> calls = new TreeSet<>();
    }

    private JarInspector() {}

    public static Inspection inspect(byte[] jar) {
        var result = new Inspection();
        result.size = jar.length;
        result.sha256 = sha256(jar);
        if (jar.length > MAX_JAR_BYTES) {
            result.issues.add(error("JAR_TOO_LARGE", "JAR 超过 20 MB", null));
            return result;
        }
        Map<String, ClassInfo> classes = new TreeMap<>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            int entries = 0;
            long total = 0;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    result.issues.add(error("JAR_TOO_MANY_ENTRIES", "JAR 条目超过 " + MAX_ENTRIES, null));
                    return result;
                }
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                    result.issues.add(error("JAR_PATH_INVALID", "JAR 含非法路径: " + name, null));
                    continue;
                }
                byte[] bytes = readBounded(in, MAX_UNCOMPRESSED_BYTES - total);
                if (bytes == null) {
                    result.issues.add(error("JAR_TOO_LARGE", "JAR 解压后超过 100 MB", null));
                    return result;
                }
                total += bytes.length;
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    result.issues.add(error("JAR_TOO_LARGE", "JAR 解压后超过 100 MB", null));
                    return result;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".so") || lower.endsWith(".dll") || lower.endsWith(".dylib") || lower.endsWith(".jnilib")) {
                    result.issues.add(error("JAR_NATIVE_LIBRARY", "不允许包含本地库: " + name, null));
                    continue;
                }
                if (!name.endsWith(".class") || name.endsWith("module-info.class") || name.endsWith("package-info.class")) continue;
                if (name.startsWith("META-INF/versions/")) {
                    result.issues.add(error("JAR_MULTI_RELEASE", "不支持多版本 JAR（META-INF/versions）: " + name, null));
                    continue;
                }
                ClassInfo info;
                try {
                    info = read(bytes);
                } catch (RuntimeException e) {
                    result.issues.add(error("CLASS_UNREADABLE", "无法解析类文件: " + name, null));
                    continue;
                }
                String binary = info.name.replace('/', '.');
                for (String reserved : RESERVED)
                    if (info.name.startsWith(reserved))
                        result.issues.add(error("RESERVED_PACKAGE", "不允许包含保留包中的类（JMeter、Octopus、JDK）: " + binary, binary));
                if (info.major > MAX_CLASS_MAJOR)
                    result.issues.add(error("BYTECODE_TOO_NEW", "类文件版本高于运行器 JVM（Java 21）: " + binary, binary));
                classes.put(info.name, info);
            }
        } catch (IOException e) {
            result.issues.add(error("JAR_UNREADABLE", "无法读取 JAR", null));
            return result;
        }
        result.classCount = classes.size();
        classes.keySet().forEach(n -> result.classNames.add(n.replace('/', '.')));
        for (ClassInfo info : classes.values()) risks(info, result);
        for (ClassInfo info : classes.values()) {
            boolean function = (info.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0 && isFunction(info.name, classes, 0);
            Ann tool = annotation(info, "OctopusTool");
            if (tool == null) {
                if (function) result.unannotatedFunctions.add(info.name.replace('/', '.'));
                continue;
            }
            result.tools.add(tool(info, tool, classes, function, result.issues));
        }
        for (String unannotated : result.unannotatedFunctions)
            result.issues.add(warning("UNANNOTATED_FUNCTION", "JMeter 函数类 " + unannotated + " 未带 @OctopusTool，不会注册为工具", unannotated));
        if (result.tools.isEmpty()) result.issues.add(error("NO_TOOLS", "JAR 中没有带 @OctopusTool 注解的 JMeter 函数类", null));
        var keys = new HashSet<String>();
        for (Tool t : result.tools)
            if (t.name != null && t.version != null && !keys.add(t.name + "@" + t.version))
                result.issues.add(error("DUPLICATE_TOOL", "同一 JAR 内重复声明 " + t.name + "@" + t.version, t.className));
        return result;
    }

    /** Reads one entry, or returns null once more than {@code limit} bytes would be needed (zip bombs). */
    private static byte[] readBounded(ZipInputStream in, long limit) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + (long) n > limit) return null;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- metadata

    private static Tool tool(ClassInfo info, Ann ann, Map<String, ClassInfo> classes, boolean function, List<Issue> issues) {
        String cls = info.name.replace('/', '.');
        var t = new Tool();
        t.className = cls;
        t.name = str(ann, "name");
        t.version = str(ann, "version");
        t.displayName = str(ann, "displayName");
        t.summary = str(ann, "summary");
        t.description = str(ann, "description");
        t.whenToUse = str(ann, "whenToUse");
        t.whenNotToUse = str(ann, "whenNotToUse");
        t.category = ann.values.containsKey("category") ? str(ann, "category") : "general";
        t.tags = strings(ann, "tags");
        t.determinism = str(ann, "determinism");
        t.seedParam = str(ann, "seedParam");
        t.stages = strings(ann, "stages");
        t.sensitiveOutput = bool(ann, "sensitiveOutput", false);
        t.owner = str(ann, "owner");
        t.deprecated = str(ann, "deprecated");
        t.sdkBase = extendsSdk(info.name, classes, 0);
        for (Ann p : repeated(info, "ToolParam", "ToolParams")) t.params.add(param(p));
        t.params.sort(Comparator.comparingInt(p -> p.index));
        Ann out = annotation(info, "ToolOutput");
        if (out != null) {
            t.output = new Output();
            t.output.type = str(out, "type");
            t.output.format = str(out, "format");
            t.output.description = str(out, "description");
            t.output.schema = str(out, "schema");
        }
        for (Ann e : repeated(info, "ToolExample", "ToolExamples")) {
            var ex = new Example();
            ex.args = strings(e, "args");
            ex.output = str(e, "output");
            ex.match = e.values.containsKey("match") ? str(e, "match") : "EXACT";
            ex.description = str(e, "description");
            t.examples.add(ex);
        }
        for (Ann e : repeated(info, "ToolError", "ToolErrors")) {
            var er = new ErrorDef();
            er.code = str(e, "code");
            er.description = str(e, "description");
            t.errors.add(er);
        }
        if (t.name != null && t.version != null && ToolNamesRules.valid(t.name, t.version)) t.referenceKey = ToolNamesRules.referenceKey(t.name, t.version);
        compliance(info, t, function, issues);
        quality(t, issues);
        return t;
    }

    private static Param param(Ann p) {
        var r = new Param();
        r.index = p.values.get("index") instanceof Integer ? (Integer) p.values.get("index") : -1;
        r.name = str(p, "name");
        r.type = str(p, "type");
        r.required = bool(p, "required", true);
        r.defaultValue = str(p, "defaultValue");
        r.description = str(p, "description");
        r.enumValues = strings(p, "enumValues");
        r.pattern = str(p, "pattern");
        r.min = str(p, "min");
        r.max = str(p, "max");
        r.format = str(p, "format");
        r.maxLength = p.values.get("maxLength") instanceof Integer ? (Integer) p.values.get("maxLength") : -1;
        r.sensitive = bool(p, "sensitive", false);
        r.example = str(p, "example");
        return r;
    }

    /** JMeter specification checks that can be decided statically (FR-EXT-04). */
    private static void compliance(ClassInfo info, Tool t, boolean function, List<Issue> issues) {
        String cls = t.className;
        if (!function) issues.add(error("NOT_A_FUNCTION", "类必须实现 org.apache.jmeter.functions.Function（建议继承 OctopusFunction）且不能是抽象类", cls));
        if ((info.access & Opcodes.ACC_PUBLIC) == 0) issues.add(error("NOT_PUBLIC", "函数类必须是 public", cls));
        if (!info.publicNoArgConstructor) issues.add(error("NO_PUBLIC_CONSTRUCTOR", "函数类需要公共无参构造器", cls));
        if (!cls.contains(".functions.") || cls.contains(".gui."))
            issues.add(error("JMETER_DISCOVERY", "JMeter 只发现类名包含 .functions. 且不包含 .gui. 的函数类", cls));
        if (t.version != null && ToolNamesRules.VERSION.matcher(t.version).matches()) {
            String segment = ToolNamesRules.versionSegment(t.version);
            String pkg = cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')) : "";
            if (!Arrays.asList(pkg.split("\\.")).contains(segment))
                issues.add(error("PACKAGE_VERSION", "包名需包含版本段 " + segment + "，使不同版本可在同一 JMeter 中共存（例如 com.acme.functions." + segment + "）", cls));
        }
        if (!t.sdkBase)
            issues.add(warning("NO_SDK_BASE", "未继承 OctopusFunction：引用键与参数描述需自行保证，将在隔离测试中核对", cls));
    }

    /** Metadata contract checks (FR-EXT-22). */
    private static void quality(Tool t, List<Issue> issues) {
        String cls = t.className;
        if (t.name == null || t.name.isEmpty() || !ToolNamesRules.NAME.matcher(t.name).matches())
            issues.add(error("NAME_INVALID", "@OctopusTool.name 需为小写命名空间名称，如 acme.sign", cls));
        if (t.version == null || !ToolNamesRules.VERSION.matcher(t.version).matches())
            issues.add(error("VERSION_INVALID", "@OctopusTool.version 需为语义化版本 MAJOR.MINOR.PATCH", cls));
        text(t.summary, 1, 120, "summary", cls, issues);
        text(t.description, 1, 2000, "description", cls, issues);
        text(t.whenToUse, 1, 500, "whenToUse", cls, issues);
        text(t.whenNotToUse, 0, 500, "whenNotToUse", cls, issues);
        if (!CATEGORY.matcher(t.category).matches()) issues.add(error("CATEGORY_INVALID", "category 需为小写短横线标识", cls));
        if (t.tags.size() > 10) issues.add(error("TAGS_TOO_MANY", "tags 最多 10 个", cls));
        if (!Set.of("PURE", "CLOCK", "RANDOM").contains(t.determinism)) issues.add(error("DETERMINISM_INVALID", "determinism 必须声明", cls));
        if (t.stages.isEmpty()) issues.add(error("STAGES_EMPTY", "stages 至少声明一个阶段", cls));
        if (t.stages.contains("EXPECTED_VALUE") && !"PURE".equals(t.determinism))
            issues.add(error("EXPECTED_VALUE_NOT_PURE", "EXPECTED_VALUE 阶段只允许 PURE 扩展（预期值不得依赖时间或随机数）", cls));
        if (!t.seedParam.isEmpty()) {
            if (!"RANDOM".equals(t.determinism)) issues.add(error("SEED_NOT_RANDOM", "只有 RANDOM 扩展可以声明 seedParam", cls));
            if (t.params.stream().noneMatch(p -> t.seedParam.equals(p.name))) issues.add(error("SEED_UNKNOWN", "seedParam 必须是已声明的参数", cls));
        }
        var names = new HashSet<String>();
        int required = 0;
        for (int i = 0; i < t.params.size(); i++) {
            Param p = t.params.get(i);
            String where = "参数 " + (p.name == null ? "#" + i : p.name) + "：";
            if (p.index != i) issues.add(error("PARAM_INDEX", where + "@ToolParam.index 需从 0 开始连续", cls));
            if (p.name == null || !ToolNamesRules.PARAM.matcher(p.name).matches() || !names.add(p.name))
                issues.add(error("PARAM_NAME", where + "名称无效或重复", cls));
            if (!TYPES.contains(p.type)) issues.add(error("PARAM_TYPE", where + "类型无效", cls));
            text(p.description, 1, 500, "参数 " + p.name + " 的 description", cls, issues);
            if (p.required) {
                if (required != i) issues.add(error("PARAM_ORDER", where + "必填参数必须排在可选参数之前", cls));
                required++;
            }
            if (!p.pattern.isEmpty()) {
                try { Pattern.compile(p.pattern); } catch (PatternSyntaxException e) { issues.add(error("PARAM_PATTERN", where + "pattern 不是合法正则", cls)); }
            }
            boolean numeric = "INTEGER".equals(p.type) || "NUMBER".equals(p.type);
            for (String bound : List.of(p.min, p.max)) {
                if (bound.isEmpty()) continue;
                if (!numeric) issues.add(error("PARAM_BOUND", where + "min/max 只适用于数值参数", cls));
                else try { new BigDecimal(bound); } catch (NumberFormatException e) { issues.add(error("PARAM_BOUND", where + "min/max 不是数字", cls)); }
            }
            if (p.enumValues.stream().anyMatch(String::isEmpty)) issues.add(error("PARAM_ENUM", where + "enumValues 不能含空值", cls));
            if (p.sensitive && (!p.example.isEmpty() || !p.defaultValue.isEmpty()))
                issues.add(error("SENSITIVE_VALUE", where + "敏感参数不能设置 example 或 defaultValue", cls));
            if (p.sensitive && !"STRING".equals(p.type)) issues.add(error("SENSITIVE_TYPE", where + "敏感参数必须是 STRING", cls));
        }
        if (t.output == null) issues.add(error("OUTPUT_MISSING", "缺少 @ToolOutput", cls));
        else {
            if (!TYPES.contains(t.output.type)) issues.add(error("OUTPUT_TYPE", "@ToolOutput.type 无效", cls));
            text(t.output.description, 1, 500, "@ToolOutput.description", cls, issues);
            if (!t.output.schema.isEmpty()) {
                if (!"OBJECT".equals(t.output.type)) issues.add(error("OUTPUT_SCHEMA", "只有 OBJECT 输出可以声明 schema", cls));
                else try {
                    if (!(io.yuan.octopus.extension.sdk.Json.parse(t.output.schema) instanceof Map)) throw new IllegalArgumentException();
                } catch (RuntimeException e) { issues.add(error("OUTPUT_SCHEMA", "schema 不是 JSON 对象", cls)); }
            }
        }
        if (t.examples.size() < 2) issues.add(error("EXAMPLES_TOO_FEW", "至少需要 2 个 @ToolExample", cls));
        var covered = new HashSet<Integer>();
        for (int i = 0; i < t.examples.size(); i++) {
            Example e = t.examples.get(i);
            String where = "示例 " + (i + 1) + "：";
            if (e.args.size() < required || e.args.size() > t.params.size()) issues.add(error("EXAMPLE_ARGS", where + "参数个数应在 " + required + "–" + t.params.size() + " 之间", cls));
            for (int a = 0; a < e.args.size(); a++) covered.add(a);
            if (!Set.of("EXACT", "REGEX").contains(e.match)) issues.add(error("EXAMPLE_MATCH", where + "match 无效", cls));
            if ("PURE".equals(t.determinism) && "REGEX".equals(e.match)) issues.add(error("EXAMPLE_PURE_REGEX", where + "PURE 扩展的示例必须给出精确输出", cls));
            if ("REGEX".equals(e.match)) {
                try { Pattern.compile(e.output); } catch (PatternSyntaxException x) { issues.add(error("EXAMPLE_REGEX", where + "输出模式不是合法正则", cls)); }
            }
            for (int a = 0; a < e.args.size() && a < t.params.size(); a++)
                if (t.params.get(a).sensitive && !e.args.get(a).matches("<[A-Za-z0-9_.-]{1,40}>"))
                    issues.add(error("EXAMPLE_SENSITIVE", where + "敏感参数 " + t.params.get(a).name + " 只能使用占位值，如 <test-secret>", cls));
        }
        for (int i = 0; i < required; i++)
            if (!covered.contains(i)) issues.add(error("EXAMPLE_COVERAGE", "示例需要覆盖全部必填参数", cls));
        var codes = new HashSet<String>();
        for (ErrorDef e : t.errors) {
            if (e.code == null || !ToolNamesRules.ERROR_CODE.matcher(e.code).matches() || !codes.add(e.code))
                issues.add(error("ERROR_CODE", "@ToolError.code 无效或重复: " + e.code, cls));
            text(e.description, 1, 300, "@ToolError " + e.code + " 的 description", cls, issues);
        }
        int budget = length(t.summary) + length(t.description) + length(t.whenToUse) + length(t.whenNotToUse);
        for (Param p : t.params) budget += length(p.description) + length(p.format) + 40;
        for (Example e : t.examples) budget += length(e.output) + length(e.description) + e.args.stream().mapToInt(JarInspector::length).sum();
        for (ErrorDef e : t.errors) budget += length(e.description) + 20;
        if (budget > MAX_TOOL_DEFINITION_CHARS)
            issues.add(error("DEFINITION_TOO_LONG", "工具定义约 " + budget + " 字符，超过 LLM 工具定义预算 " + MAX_TOOL_DEFINITION_CHARS + "，请精简说明或示例", cls));
    }

    private static void text(String value, int min, int max, String field, String cls, List<Issue> issues) {
        int n = length(value);
        if (n < min) issues.add(error("TEXT_MISSING", field + " 不能为空", cls));
        else if (n > max) issues.add(error("TEXT_TOO_LONG", field + " 超过 " + max + " 字符", cls));
    }

    private static int length(String s) { return s == null ? 0 : s.length(); }

    // ---------------------------------------------------------------- risk scan (FR-EXT-23)

    private static final List<String[]> REJECT = List.of(
            new String[] {"java/lang/Runtime", "exec", "执行外部进程"},
            new String[] {"java/lang/Runtime", "halt", "终止 JVM"},
            new String[] {"java/lang/Runtime", "exit", "终止 JVM"},
            new String[] {"java/lang/System", "exit", "终止 JVM"},
            new String[] {"java/lang/ProcessBuilder", "*", "执行外部进程"},
            new String[] {"java/lang/ProcessHandle", "*", "访问外部进程"},
            new String[] {"java/net/http/", "*", "网络访问"},
            new String[] {"javax/net/", "*", "网络访问"},
            new String[] {"java/nio/channels/SocketChannel", "*", "网络访问"},
            new String[] {"java/nio/channels/ServerSocketChannel", "*", "网络访问"},
            new String[] {"java/nio/channels/DatagramChannel", "*", "网络访问"},
            new String[] {"java/rmi/", "*", "远程调用"},
            new String[] {"javax/naming/", "*", "JNDI 访问"},
            new String[] {"javax/script/", "*", "脚本引擎"},
            new String[] {"groovy/", "*", "脚本引擎"},
            new String[] {"org/codehaus/groovy/", "*", "脚本引擎"},
            new String[] {"bsh/", "*", "脚本引擎"},
            new String[] {"org/apache/http/", "*", "网络访问"},
            new String[] {"org/apache/hc/", "*", "网络访问"},
            new String[] {"sun/misc/Unsafe", "*", "不安全内存访问"},
            new String[] {"jdk/internal/", "*", "JDK 内部 API"});
    /** {@code java/net/} classes that do not open connections. */
    private static final Set<String> NET_ALLOWED = Set.of("java/net/URI", "java/net/URLEncoder", "java/net/URLDecoder");
    private static final List<String[]> WARN = List.of(
            new String[] {"java/io/File", "*", "文件访问"},
            new String[] {"java/io/FileInputStream", "*", "文件访问"},
            new String[] {"java/io/FileOutputStream", "*", "文件访问"},
            new String[] {"java/io/FileReader", "*", "文件访问"},
            new String[] {"java/io/FileWriter", "*", "文件访问"},
            new String[] {"java/io/RandomAccessFile", "*", "文件访问"},
            new String[] {"java/nio/file/", "*", "文件访问"},
            new String[] {"java/lang/reflect/", "*", "反射"},
            new String[] {"java/lang/Class", "forName", "反射"},
            new String[] {"java/lang/Class", "getDeclaredMethod", "反射"},
            new String[] {"java/lang/Class", "getDeclaredField", "反射"},
            new String[] {"java/lang/ClassLoader", "*", "类加载"},
            new String[] {"java/lang/System", "setProperty", "修改系统属性"},
            new String[] {"java/lang/System", "clearProperty", "修改系统属性"},
            new String[] {"java/lang/System", "getenv", "读取环境变量"},
            new String[] {"java/lang/Thread", "<init>", "创建线程"},
            new String[] {"java/lang/Thread", "start", "创建线程"},
            new String[] {"java/util/concurrent/Executors", "*", "创建线程"},
            new String[] {"org/apache/jmeter/threads/JMeterVariables", "put", "写入 JMeter 变量"},
            new String[] {"org/apache/jmeter/threads/JMeterVariables", "putObject", "写入 JMeter 变量"},
            new String[] {"org/apache/jmeter/threads/JMeterVariables", "remove", "写入 JMeter 变量"},
            new String[] {"org/apache/jmeter/threads/JMeterVariables", "get", "读取 JMeter 变量"},
            new String[] {"org/apache/jmeter/util/JMeterUtils", "setProperty", "修改 JMeter 属性"},
            new String[] {"org/apache/jmeter/threads/JMeterContext", "getPreviousResult", "读取上一个采样结果"},
            new String[] {"org/apache/jmeter/threads/JMeterContext", "getVariables", "读取 JMeter 变量"},
            new String[] {"org/apache/jmeter/samplers/SampleResult", "*", "读取采样结果"});

    private static void risks(ClassInfo info, Inspection result) {
        String cls = info.name.replace('/', '.');
        var reported = new HashSet<String>();
        for (String call : info.calls) {
            int dot = call.indexOf('#');
            String owner = call.substring(0, dot), method = call.substring(dot + 1);
            boolean rejected = false;
            if (owner.startsWith("java/net/") && !NET_ALLOWED.contains(owner)) {
                if (reported.add("net")) result.issues.add(error("RISK_NETWORK", cls + " 使用了网络 API（" + owner.replace('/', '.') + "），不允许", cls));
                continue;
            }
            for (String[] rule : REJECT)
                if (matches(owner, method, rule)) {
                    rejected = true;
                    if (reported.add(rule[2] + owner))
                        result.issues.add(error("RISK_FORBIDDEN", cls + " 调用了 " + owner.replace('/', '.') + "." + method + "（" + rule[2] + "），不允许", cls));
                    break;
                }
            if (rejected) continue;
            for (String[] rule : WARN)
                if (matches(owner, method, rule) && reported.add(rule[2]))
                    result.issues.add(warning("RISK_" + riskCode(rule[2]), cls + " 存在风险调用：" + rule[2] + "（" + owner.replace('/', '.') + "." + method + "）", cls));
        }
    }

    private static String riskCode(String label) {
        switch (label) {
            case "文件访问": return "FILE";
            case "反射": return "REFLECTION";
            case "类加载": return "CLASSLOADER";
            case "修改系统属性": return "SYSTEM_PROPERTY";
            case "读取环境变量": return "ENVIRONMENT";
            case "创建线程": return "THREAD";
            case "写入 JMeter 变量": return "JMETER_WRITE";
            case "修改 JMeter 属性": return "JMETER_PROPERTY";
            default: return "JMETER_READ";
        }
    }

    private static boolean matches(String owner, String method, String[] rule) {
        boolean ownerMatch = rule[0].endsWith("/") ? owner.startsWith(rule[0]) : owner.equals(rule[0]);
        return ownerMatch && ("*".equals(rule[1]) || rule[1].equals(method));
    }

    // ---------------------------------------------------------------- hierarchy

    private static boolean isFunction(String name, Map<String, ClassInfo> classes, int depth) {
        if (name == null || depth > 20) return false;
        if (name.equals(FUNCTION) || name.equals(ABSTRACT_FUNCTION) || name.equals(OCTOPUS_FUNCTION)) return true;
        ClassInfo c = classes.get(name);
        if (c == null) return false;
        for (String i : c.interfaces) if (isFunction(i, classes, depth + 1)) return true;
        return isFunction(c.superName, classes, depth + 1);
    }

    private static boolean extendsSdk(String name, Map<String, ClassInfo> classes, int depth) {
        if (name == null || depth > 20) return false;
        if (name.equals(OCTOPUS_FUNCTION)) return true;
        ClassInfo c = classes.get(name);
        return c != null && extendsSdk(c.superName, classes, depth + 1);
    }

    // ---------------------------------------------------------------- ASM reading

    private static ClassInfo read(byte[] bytes) {
        var info = new ClassInfo();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                info.name = name;
                info.superName = superName;
                info.interfaces = interfaces == null ? List.of() : List.of(interfaces);
                info.access = access;
                info.major = version & 0xFFFF;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                var ann = new Ann(descriptor);
                info.annotations.add(ann);
                return new MapCollector(ann.values);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ("<init>".equals(name) && "()V".equals(descriptor) && (access & Opcodes.ACC_PUBLIC) != 0) info.publicNoArgConstructor = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        info.calls.add(owner + "#" + method);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW) info.calls.add(type + "#<init>");
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        info.calls.add(owner + "#" + field);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String method, String desc, Handle bootstrap, Object... arguments) {
                        for (Object argument : arguments)
                            if (argument instanceof Handle) info.calls.add(((Handle) argument).getOwner() + "#" + ((Handle) argument).getName());
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type && ((Type) value).getSort() == Type.OBJECT) info.calls.add(((Type) value).getInternalName() + "#class");
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return info;
    }

    private static final class MapCollector extends AnnotationVisitor {
        private final Map<String, Object> target;
        MapCollector(Map<String, Object> target) { super(Opcodes.ASM9); this.target = target; }
        @Override public void visit(String name, Object value) {
            if (value != null && value.getClass().isArray()) {
                var list = new ArrayList<Object>();
                int n = java.lang.reflect.Array.getLength(value);
                for (int i = 0; i < n; i++) list.add(java.lang.reflect.Array.get(value, i));
                target.put(name, list);
            } else target.put(name, value);
        }
        @Override public void visitEnum(String name, String descriptor, String value) { target.put(name, value); }
        @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            var ann = new Ann(descriptor);
            target.put(name, ann);
            return new MapCollector(ann.values);
        }
        @Override public AnnotationVisitor visitArray(String name) {
            var list = new ArrayList<Object>();
            target.put(name, list);
            return new ListCollector(list);
        }
    }

    private static final class ListCollector extends AnnotationVisitor {
        private final List<Object> target;
        ListCollector(List<Object> target) { super(Opcodes.ASM9); this.target = target; }
        @Override public void visit(String name, Object value) { target.add(value); }
        @Override public void visitEnum(String name, String descriptor, String value) { target.add(value); }
        @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            var ann = new Ann(descriptor);
            target.add(ann);
            return new MapCollector(ann.values);
        }
        @Override public AnnotationVisitor visitArray(String name) {
            var list = new ArrayList<Object>();
            target.add(list);
            return new ListCollector(list);
        }
    }

    private static Ann annotation(ClassInfo info, String simple) {
        String desc = "L" + SDK + simple + ";";
        for (Ann a : info.annotations) if (a.desc.equals(desc)) return a;
        return null;
    }

    /** Repeatable annotations appear either directly (one use) or inside their container (several uses). */
    private static List<Ann> repeated(ClassInfo info, String simple, String container) {
        var result = new ArrayList<Ann>();
        Ann single = annotation(info, simple);
        if (single != null) result.add(single);
        Ann holder = annotation(info, container);
        if (holder != null && holder.values.get("value") instanceof List)
            for (Object o : (List<?>) holder.values.get("value")) if (o instanceof Ann) result.add((Ann) o);
        return result;
    }

    private static String str(Ann a, String key) {
        Object v = a.values.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean bool(Ann a, String key, boolean fallback) {
        Object v = a.values.get(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static List<String> strings(Ann a, String key) {
        Object v = a.values.get(key);
        var result = new ArrayList<String>();
        if (v instanceof List) for (Object o : (List<?>) v) result.add(String.valueOf(o));
        return result;
    }

    private static Issue error(String code, String message, String cls) { return new Issue("ERROR", code, message, cls); }
    private static Issue warning(String code, String message, String cls) { return new Issue("WARNING", code, message, cls); }

    public static String sha256(byte[] bytes) {
        try {
            var hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mirrors {@code io.yuan.octopus.extension.sdk.ToolNames}. */
    static final class ToolNamesRules {
        static final Pattern NAME = io.yuan.octopus.extension.sdk.ToolNames.NAME;
        static final Pattern VERSION = io.yuan.octopus.extension.sdk.ToolNames.VERSION;
        static final Pattern PARAM = io.yuan.octopus.extension.sdk.ToolNames.PARAM;
        static final Pattern ERROR_CODE = io.yuan.octopus.extension.sdk.ToolNames.ERROR_CODE;
        static boolean valid(String name, String version) { return NAME.matcher(name).matches() && VERSION.matcher(version).matches(); }
        static String referenceKey(String name, String version) { return io.yuan.octopus.extension.sdk.ToolNames.referenceKey(name, version); }
        static String versionSegment(String version) { return io.yuan.octopus.extension.sdk.ToolNames.versionSegment(version); }
    }
}
