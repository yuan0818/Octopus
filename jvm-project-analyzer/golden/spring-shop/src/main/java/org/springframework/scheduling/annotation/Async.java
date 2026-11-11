package org.springframework.scheduling.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.TYPE}) public @interface Async {}
