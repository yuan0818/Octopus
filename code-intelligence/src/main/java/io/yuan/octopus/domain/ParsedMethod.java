package io.yuan.octopus.domain;

import java.util.List;

public record ParsedMethod(
    String name,
    String signature,
    List<String> parameterTypes,
    String returnType,
    List<String> modifiers,
    String sourceCode,
    List<MethodCall> methodCalls,
    List<ParsedAnnotation> annotations,
    /** 方法声明行号（从 1 计数），无行号信息时为 null。 */
    Integer lineStart,
    /** 方法体结束行号（从 1 计数），无行号信息时为 null。 */
    Integer lineEnd
) {
    /** 向后兼容：无行号信息的构造器。 */
    public ParsedMethod(String name, String signature, List<String> parameterTypes, String returnType,
                        List<String> modifiers, String sourceCode,
                        List<MethodCall> methodCalls, List<ParsedAnnotation> annotations) {
        this(name, signature, parameterTypes, returnType, modifiers, sourceCode,
                methodCalls, annotations, null, null);
    }
}
