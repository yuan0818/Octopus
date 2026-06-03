package io.yuan.octopus.exception;

/**
 * Exception for Git operation failures.
 */
public class GitOperationException extends OctopusException {

    public GitOperationException(String message) {
        super(message);
    }

    public GitOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
