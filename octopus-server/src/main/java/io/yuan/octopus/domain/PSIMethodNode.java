package io.yuan.octopus.domain;

import java.util.List;

/**
 * Method node produced by the IntelliJ PSI analyzer.
 * Carries Spring-specific metadata (Bean annotations, Bean name) that
 * the plain {@link MethodNode} does not track.
 */
public record PSIMethodNode(
    String qualifiedClassName,
    String methodName,
    List<String> parameterTypes,
    String returnType,
    String modifiers,
    String signature,
    List<String> springAnnotations,
    boolean isBean,
    String beanName,
    String sourceFilePath,
    String sourceCode
) {}
