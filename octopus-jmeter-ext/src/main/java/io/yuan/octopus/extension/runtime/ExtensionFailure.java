package io.yuan.octopus.extension.runtime;

/** A fixed, non-sensitive failure code; arguments and outputs are never included. */
public final class ExtensionFailure extends RuntimeException {
    public final String code;

    public ExtensionFailure(String code) {
        super(code);
        this.code = code;
    }
}
