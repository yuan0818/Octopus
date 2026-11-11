package org.springframework.context.event;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public @interface EventListener { Class<?>[] value() default {}; Class<?>[] classes() default {}; }
