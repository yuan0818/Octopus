package io.yuan.octopus.common;

public enum JavaVersion {
    JAVA_8(8, "1.8"),
    JAVA_11(11, "11"),
    JAVA_17(17, "17"),
    JAVA_21(21, "21");

    private final int majorVersion;
    private final String versionString;

    JavaVersion(int majorVersion, String versionString) {
        this.majorVersion = majorVersion;
        this.versionString = versionString;
    }

    public int getMajorVersion() { return majorVersion; }
    public String getVersionString() { return versionString; }

    public static JavaVersion fromMajorVersion(int version) {
        if (version <= 8) return JAVA_8;
        if (version <= 11) return JAVA_11;
        if (version <= 17) return JAVA_17;
        return JAVA_21;
    }

    public static JavaVersion defaultVersion() {
        return JAVA_17;
    }
}
