package io.yuan.octopus.exception;

/**
 * Exception for 404 resource not found cases.
 */
public class ResourceNotFoundException extends OctopusException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
