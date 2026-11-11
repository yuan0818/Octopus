package io.yuan.octopus.extension.sdk;

import java.lang.annotation.*;

/**
 * Declares a JMeter function class as an Octopus tool. The platform reads this annotation from the
 * uploaded JAR's bytecode (without loading the class) to register the tool for people and LLMs.
 *
 * <p>The annotated class must implement {@code org.apache.jmeter.functions.Function} (usually by
 * extending {@link OctopusFunction}), have a public no-argument constructor, and live in a package whose
 * name contains {@code .functions.} and a version segment, e.g. {@code com.acme.functions.v1_0_0}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface OctopusTool {
    /** Namespaced name, lower case: {@code acme.sign}. */
    String name();
    /** Semantic version, {@code MAJOR.MINOR.PATCH}. A published version can never change. */
    String version();
    String displayName() default "";
    /** One sentence, at most 120 characters. Used in search results. */
    String summary();
    /** Detailed explanation, at most 2,000 characters. */
    String description();
    /** When the tool should be used. */
    String whenToUse();
    /** When the tool must not be used. */
    String whenNotToUse() default "";
    String category() default "general";
    String[] tags() default {};
    Determinism determinism();
    /** For {@link Determinism#RANDOM}: the parameter that accepts a seed, if supported. */
    String seedParam() default "";
    Stage[] stages();
    /** Whether the output is sensitive; sensitive outputs never reach reports, logs or model context. */
    boolean sensitiveOutput() default false;
    String owner() default "";
    /** Non-empty marks the version deprecated; the text should name the replacement. */
    String deprecated() default "";
}
