package io.yuan.octopus.exception;

/**
 * Exception for 400 validation failure cases.
 */
public class ValidationException extends io.yuan.octopus.infra.common.OctopusException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
