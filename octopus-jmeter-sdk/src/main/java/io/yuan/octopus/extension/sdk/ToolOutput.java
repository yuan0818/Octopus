package io.yuan.octopus.extension.sdk;

import java.lang.annotation.*;

/** The single value the tool returns. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ToolOutput {
    ParamType type();
    String format() default "";
    String description();
    /** JSON Schema of an {@link ParamType#OBJECT} output, as JSON text. */
    String schema() default "";
}
