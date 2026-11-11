package io.yuan.octopus.extension.sdk;

import java.lang.annotation.*;

/** Container for repeated {@link ToolExample}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ToolExamples { ToolExample[] value(); }
