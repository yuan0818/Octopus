package io.yuan.octopus.extension.sdk;

import java.lang.annotation.*;

/** One positional JMeter function parameter. Indexes are 0-based and contiguous. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ToolParams.class)
@Documented
public @interface ToolParam {
    int index();
    /** Identifier: letters, digits and underscore, starting with a letter. */
    String name();
    ParamType type();
    boolean required() default true;
    /** Default for an optional parameter, as text. */
    String defaultValue() default "";
    String description();
    String[] enumValues() default {};
    /** Java regular expression the text value must fully match. */
    String pattern() default "";
    /** Inclusive numeric bounds, as text; empty means unbounded. */
    String min() default "";
    String max() default "";
    /** Free-form format hint, e.g. {@code yyyy-MM-dd} or {@code hex-64}. */
    String format() default "";
    int maxLength() default -1;
    /** Sensitive parameters may only be bound to secrets; never put real values in examples. */
    boolean sensitive() default false;
    String example() default "";
}
