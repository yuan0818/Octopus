package io.yuan.octopus.common;

public enum CallType {
    // Basic call types (from JavaParser static analysis)
    DIRECT, INTERFACE, SUPER, LAMBDA,

    // Spring dependency injection edges
    SPRING_DI,

    // AOP advice edges
    AOP_BEFORE, AOP_AROUND, AOP_AFTER, AOP_AFTER_RETURNING, AOP_AFTER_THROWING,

    // Event-driven edges
    EVENT_PUBLISH,

    // Scheduling / async edges
    SCHEDULED, ASYNC,

    // Spring Data repository methods
    REPOSITORY,

    // HTTP remote calls (Feign, RestTemplate, WebClient)
    HTTP_CLIENT,

    // Bean lifecycle edges
    BEAN_INIT
}
