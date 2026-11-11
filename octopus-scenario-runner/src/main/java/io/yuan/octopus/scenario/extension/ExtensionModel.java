package io.yuan.octopus.scenario.extension;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata parsed from an uploaded extension JAR. Plain data with public fields: it is stored as JSON,
 * rendered as documentation and converted into LLM tool definitions, all from this single source.
 */
public final class ExtensionModel {
    private ExtensionModel() {}

    /** One {@code @OctopusTool} class. */
    public static final class Tool {
        public String className;
        public String name;
        public String version;
        public String referenceKey;
        public String displayName = "";
        public String summary = "";
        public String description = "";
        public String whenToUse = "";
        public String whenNotToUse = "";
        public String category = "general";
        public List<String> tags = new ArrayList<>();
        public String determinism;
        public String seedParam = "";
        public List<String> stages = new ArrayList<>();
        public boolean sensitiveOutput;
        public String owner = "";
        public String deprecated = "";
        public List<Param> params = new ArrayList<>();
        public Output output;
        public List<Example> examples = new ArrayList<>();
        public List<ErrorDef> errors = new ArrayList<>();
        /** Whether the class extends the SDK base class (reference key and argument checks are then generated). */
        public boolean sdkBase;

        public String catalogId() {
            return "x." + name;
        }

        public String key() {
            return catalogId() + "@" + version;
        }
    }

    public static final class Param {
        public int index;
        public String name;
        public String type;
        public boolean required = true;
        public String defaultValue = "";
        public String description = "";
        public List<String> enumValues = new ArrayList<>();
        public String pattern = "";
        public String min = "";
        public String max = "";
        public String format = "";
        public int maxLength = -1;
        public boolean sensitive;
        public String example = "";
    }

    public static final class Output {
        public String type;
        public String format = "";
        public String description = "";
        public String schema = "";
    }

    public static final class Example {
        public List<String> args = new ArrayList<>();
        public String output;
        public String match = "EXACT";
        public String description = "";
    }

    public static final class ErrorDef {
        public String code;
        public String description;
    }

    /** A finding; ERROR blocks publication, WARNING is shown to users and in the tool documentation. */
    public static final class Issue {
        public String severity;
        public String code;
        public String message;
        public String className;

        public Issue() {}

        public Issue(String severity, String code, String message, String className) {
            this.severity = severity;
            this.code = code;
            this.message = message;
            this.className = className;
        }

        public boolean error() {
            return "ERROR".equals(severity);
        }
    }

    /** Static inspection result of one JAR; produced without loading or executing any uploaded class. */
    public static final class Inspection {
        public String sha256;
        public long size;
        public int classCount;
        public List<String> classNames = new ArrayList<>();
        public List<Tool> tools = new ArrayList<>();
        /** JMeter function classes without {@code @OctopusTool}: listed, never registered. */
        public List<String> unannotatedFunctions = new ArrayList<>();
        public List<Issue> issues = new ArrayList<>();

        public boolean blocked() {
            return issues.stream().anyMatch(Issue::error) || tools.isEmpty();
        }
    }
}
