package org.aspectj.lang.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public @interface Before { String value(); }
