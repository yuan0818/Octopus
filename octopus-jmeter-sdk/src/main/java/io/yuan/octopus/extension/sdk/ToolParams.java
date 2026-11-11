package io.yuan.octopus.extension.sdk;

import java.lang.annotation.*;

/** Container for repeated {@link ToolParam}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ToolParams { ToolParam[] value(); }
