package io.yuan.octopus.extension.sdk;

/** How an extension's output relates to its inputs. Expected values may only use {@link #PURE}. */
public enum Determinism {
    /** Same arguments always produce the same output. */
    PURE,
    /** Output depends on the current time. */
    CLOCK,
    /** Output is random; may declare a seed parameter. */
    RANDOM
}
