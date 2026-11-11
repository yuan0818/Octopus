package io.yuan.octopus.extension.sdk;

import java.util.regex.Pattern;

/** Naming rules shared by the SDK, the platform's upload checks and the JMeter runtime. */
public final class ToolNames {
    /** Prefix of a failure result. JMeter functions return text, so failures are encoded in-band. */
    public static final String ERROR_PREFIX = "\u0000octopus-error:";
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]{0,30}(\\.[a-z][a-z0-9_]{0,40}){1,3}");
    public static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})");
    public static final Pattern PARAM = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    public static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    private ToolNames() {}

    /** Version segment used in reference keys and packages: {@code 1.2.3 -> v1_2_3}. */
    public static String versionSegment(String version) {
        return "v" + version.replace('.', '_');
    }

    /** JMeter reference key: {@code acme.sign@1.0.0 -> __acme_sign_v1_0_0}. Unique per version so versions coexist. */
    public static String referenceKey(String name, String version) {
        return "__" + name.replace('.', '_') + "_" + versionSegment(version);
    }

    /** Catalog id used in preparation plans: {@code x.acme.sign}. */
    public static String catalogId(String name) {
        return "x." + name;
    }

    public static String error(String code) {
        return ERROR_PREFIX + code;
    }

    public static boolean isError(String result) {
        return result != null && result.startsWith(ERROR_PREFIX);
    }

    public static String errorCode(String result) {
        return isError(result) ? result.substring(ERROR_PREFIX.length()) : null;
    }
}
