package org.springframework.scheduling.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public @interface Scheduled { String cron() default ""; long fixedDelay() default -1; long fixedRate() default -1; }
