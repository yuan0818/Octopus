package io.yuan.octopus.extension.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * One published extension version, as written by the platform into {@code octopus-extensions.json}
 * next to the runtime JAR. Plain fields so the manifest stays a simple, stable JSON document.
 */
public final class ExtensionEntry {
    /** Catalog id used in preparation plans: {@code x.<name>}. */
    public String id;
    public String name;
    public String version;
    public String className;
    public String referenceKey;
    /** SHA-256 of the extension JAR; checked before a run uses this version. */
    public String sha256;
    /** File name of the JAR inside {@code lib/ext}. */
    public String jar;
    public String determinism;
    public List<String> stages = new ArrayList<>();
    public boolean sensitiveOutput;
    public String outputType;
    public List<Param> params = new ArrayList<>();

    public static final class Param {
        public String name;
        public String type;
        public boolean required;
        public boolean sensitive;

        public Param() {}

        public Param(String name, String type, boolean required, boolean sensitive) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.sensitive = sensitive;
        }
    }

    public String key() {
        return id + "@" + version;
    }
}
