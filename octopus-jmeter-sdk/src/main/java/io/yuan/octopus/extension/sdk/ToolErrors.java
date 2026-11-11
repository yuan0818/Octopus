package io.yuan.octopus.extension.sdk;

import java.lang.annotation.*;

/** Container for repeated {@link ToolError}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ToolErrors { ToolError[] value(); }
