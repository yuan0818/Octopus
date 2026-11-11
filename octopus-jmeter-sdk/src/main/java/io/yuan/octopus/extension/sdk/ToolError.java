package io.yuan.octopus.extension.sdk;

import java.lang.annotation.*;

/** A declared error code: what input causes it and how to fix it. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ToolErrors.class)
@Documented
public @interface ToolError {
    /** Upper-case identifier, e.g. {@code SIGN_BODY_INVALID}. */
    String code();
    String description();
}
