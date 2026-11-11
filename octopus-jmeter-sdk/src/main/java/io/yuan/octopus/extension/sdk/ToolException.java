package io.yuan.octopus.extension.sdk;

/** Thrown by {@link OctopusFunction#compute}; the code should be declared with {@link ToolError}. */
public class ToolException extends Exception {
    private final String code;

    public ToolException(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}
