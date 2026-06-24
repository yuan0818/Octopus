package io.yuan.octopus.exception;

/**
 * Exception for Neo4j graph store failures.
 */
public class GraphStoreException extends OctopusException {

    public GraphStoreException(String message) {
        super(message);
    }

    public GraphStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
