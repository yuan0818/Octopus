package io.yuan.octopus.extension.sdk;

import java.lang.annotation.*;

/**
 * An example call. Examples are documentation, few-shot examples for LLMs and registration tests at once:
 * the platform executes them in an isolated JMeter process before the tool can be published.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ToolExamples.class)
@Documented
public @interface ToolExample {
    /** Positional arguments as text, matching {@link ToolParam#index()}. */
    String[] args();
    /** Exact output for PURE tools; for {@link Match#REGEX}, a Java regular expression. */
    String output();
    Match match() default Match.EXACT;
    String description() default "";

    enum Match { EXACT, REGEX }
}
