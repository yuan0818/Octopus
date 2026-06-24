package io.yuan.octopus.exception;

/**
 * Bridge to {@link io.yuan.octopus.infra.common.OctopusException}.
 *
 * @deprecated Use {@code io.yuan.octopus.infra.common.OctopusException} instead.
 */
@Deprecated
public class OctopusException extends io.yuan.octopus.infra.common.OctopusException {
    public OctopusException(String message) {
        super(message);
    }
    public OctopusException(String message, Throwable cause) {
        super(message, cause);
    }
}
