package io.yuan.octopus.extension.sdk;

/** Fixed positions in a transaction node's internal pipeline where the platform may call an extension. */
public enum Stage {
    /** Compute one request value (field, header or query parameter). */
    REQUEST_VALUE,
    /** Transform the whole request (encrypt, sign); the body/headers are passed explicitly as parameters. */
    REQUEST_TRANSFORM,
    /** Decode the response (decrypt, unwrap); the response body is passed explicitly as a parameter. */
    RESPONSE_DECODE,
    /** Compute an expected value or a data-verification parameter. Requires {@link Determinism#PURE}. */
    EXPECTED_VALUE
}
