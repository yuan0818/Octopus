package org.springframework.stereotype;
import java.lang.annotation.*;
/** Golden-test stub: same FQN as Spring, so PSI resolves without downloading dependencies. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) public @interface Service { String value() default ""; }
