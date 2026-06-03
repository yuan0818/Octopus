package io.yuan.octopus.exception;

/**
 * Exception for analysis pipeline failures.
 */
public class AnalysisException extends OctopusException {

    public AnalysisException(String message) {
        super(message);
    }

    public AnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
