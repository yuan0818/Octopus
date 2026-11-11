package io.yuan.octopus.extension.runtime;

import java.util.*;

/**
 * Whitelisted JMeter 5.6.3 built-in functions (FR-FN-06). Only functions that compute a value from their
 * arguments are listed, and parameters that would write a JMeter variable ({@code varName}) are never
 * exposed. Excluded on purpose: code/expression evaluation ({@code __groovy}, {@code __javaScript},
 * {@code __BeanShell}, {@code __jexl*}, {@code __eval*}), state changes ({@code __setProperty},
 * {@code __split}, {@code __V}, {@code __counter}), file access ({@code __CSVRead}, {@code __StringFromFile},
 * {@code __FileToString}, {@code __XPath}), environment disclosure ({@code __machineIP}, {@code __machineName},
 * {@code __TestPlanName}, {@code __threadNum}) and conditions ({@code __isVarDefined}, {@code __isPropDefined}).
 */
public final class BuiltinFunctions {
    public static final String VERSION = "5.6.3";
    private static final String PKG = "org.apache.jmeter.functions.";
    private static final Map<String, ExtensionEntry> ENTRIES = new LinkedHashMap<>();

    static {
        add("jm.time", "TimeFunction", "__time", "CLOCK", "STRING", p("format", "STRING", false));
        add("jm.uuid", "Uuid", "__UUID", "RANDOM", "STRING");
        add("jm.digest", "DigestEncodeFunction", "__digest", "PURE", "STRING",
                p("algorithm", "STRING", true), p("text", "STRING", true), p("salt", "STRING", false), p("upper", "BOOLEAN", false));
        add("jm.timeShift", "TimeShift", "__timeShift", "CLOCK", "STRING",
                p("format", "STRING", false), p("date", "STRING", false), p("shift", "STRING", false));
        add("jm.dateTimeConvert", "DateTimeConvertFunction", "__dateTimeConvert", "PURE", "STRING",
                p("date", "STRING", true), p("sourceFormat", "STRING", false), p("targetFormat", "STRING", true));
        add("jm.randomString", "RandomString", "__RandomString", "RANDOM", "STRING", p("length", "INTEGER", true), p("chars", "STRING", false));
        add("jm.random", "Random", "__Random", "RANDOM", "STRING", p("min", "INTEGER", true), p("max", "INTEGER", true));
        add("jm.urlencode", "UrlEncode", "__urlencode", "PURE", "STRING", p("text", "STRING", true));
        add("jm.urldecode", "UrlDecode", "__urldecode", "PURE", "STRING", p("text", "STRING", true));
        add("jm.changeCase", "ChangeCase", "__changeCase", "PURE", "STRING", p("text", "STRING", true), p("mode", "STRING", true));
        add("jm.char", "CharFunction", "__char", "PURE", "STRING", p("codes", "STRING", true));
    }

    private BuiltinFunctions() {}

    public static boolean isBuiltin(String id) {
        return id != null && id.startsWith("jm.");
    }

    public static Optional<ExtensionEntry> find(String id, String version) {
        ExtensionEntry e = ENTRIES.get(id);
        return e != null && VERSION.equals(version) ? Optional.of(e) : Optional.empty();
    }

    public static List<ExtensionEntry> all() {
        return List.copyOf(ENTRIES.values());
    }

    private static ExtensionEntry.Param p(String name, String type, boolean required) {
        return new ExtensionEntry.Param(name, type, required, false);
    }

    private static void add(String id, String cls, String key, String determinism, String output, ExtensionEntry.Param... params) {
        var e = new ExtensionEntry();
        e.id = id;
        e.name = id;
        e.version = VERSION;
        e.className = PKG + cls;
        e.referenceKey = key;
        e.determinism = determinism;
        e.stages = new ArrayList<>(List.of("REQUEST_VALUE"));
        if ("PURE".equals(determinism)) e.stages.add("EXPECTED_VALUE");
        e.outputType = output;
        e.params = List.of(params);
        e.jar = "ApacheJMeter_functions.jar";
        ENTRIES.put(id, e);
    }
}
