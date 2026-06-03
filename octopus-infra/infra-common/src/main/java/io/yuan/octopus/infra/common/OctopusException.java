package io.yuan.octopus.infra.common;

/**
 * Base exception for all Octopus business exceptions.
 */
public class OctopusException extends RuntimeException {

    public OctopusException(String message) {
        super(message);
    }

    public OctopusException(String message, Throwable cause) {
        super(message, cause);
    }
}
