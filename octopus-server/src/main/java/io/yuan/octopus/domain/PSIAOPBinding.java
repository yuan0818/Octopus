package io.yuan.octopus.domain;

import java.util.List;

/**
 * Spring AOP aspect-to-target-method binding extracted by the PSI analyzer.
 */
public record PSIAOPBinding(
    String aspectClassName,
    String aspectMethodName,
    String adviceType,
    String pointcutExpression,
    List<String> matchedMethodSignatures
) {}
