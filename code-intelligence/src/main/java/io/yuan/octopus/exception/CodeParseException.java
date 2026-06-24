package io.yuan.octopus.exception;

/**
 * Exception for code parsing failures.
 */
public class CodeParseException extends OctopusException {

    public CodeParseException(String message) {
        super(message);
    }

    public CodeParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
