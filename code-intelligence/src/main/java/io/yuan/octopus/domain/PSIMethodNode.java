package io.yuan.octopus.domain;

import java.util.List;

/**
 * Method node produced by the IntelliJ PSI analyzer.
 * Carries Spring-specific metadata (Bean annotations, Bean name) that
 * the plain {@link MethodNode} does not track.
 * <p>
 * {@code lineStart} / {@code lineEnd} 为方法在源文件中的行号范围（从 1 计数），
 * 由 PSI 分析器写入，供 {@code ReconstructService} 通过 JGit git show
 * 按行号提取方法体。旧版 PSI 分析器未输出这两个字段，反序列化时为 null。
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
    String sourceCode,
    Integer lineStart,
    Integer lineEnd
) {
    /** 向后兼容：无行号信息的构造器（旧调用方 / 旧 PSI 输出反序列化）。 */
    public PSIMethodNode(String qualifiedClassName, String methodName,
                         List<String> parameterTypes, String returnType, String modifiers,
                         String signature, List<String> springAnnotations,
                         boolean isBean, String beanName,
                         String sourceFilePath, String sourceCode) {
        this(qualifiedClassName, methodName, parameterTypes, returnType, modifiers, signature,
                springAnnotations, isBean, beanName, sourceFilePath, sourceCode, null, null);
    }
}
