package com.sol.cache.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Cached {

    String cacheName() default "apiResponses";
    String key() default "";
    long ttlSeconds() default 0;
    String activeProfile() default "*";
}
