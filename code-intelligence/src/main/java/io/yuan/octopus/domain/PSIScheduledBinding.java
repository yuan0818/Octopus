package io.yuan.octopus.domain;

/**
 * @Scheduled method binding extracted by the PSI analyzer.
 * Scheduled methods have no source-code caller; the framework invokes them.
 */
public record PSIScheduledBinding(
    String methodSignature,
    String qualifiedClassName,
    String cronExpression,
    String fixedDelay,
    String fixedRate
) {}
