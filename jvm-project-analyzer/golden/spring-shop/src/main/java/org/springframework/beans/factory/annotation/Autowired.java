package org.springframework.beans.factory.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.FIELD}) public @interface Autowired {}
