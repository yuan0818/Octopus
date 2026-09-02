package io.yuan.octopus.review.service;

import java.util.List;

/**
 * Schema 校验失败时抛出的异常。
 * <p>
 * 包含详细的错误列表，供 {@link SkillExecutor} 在重试时使用。
 */
public class SchemaValidationException extends RuntimeException {

    private final List<String> errors;

    public SchemaValidationException(String message, List<String> errors) {
        super(message + ": " + String.join("; ", errors));
        this.errors = errors;
    }

    public SchemaValidationException(String message) {
        super(message);
        this.errors = List.of(message);
    }

    /** 返回具体的校验错误列表。 */
    public List<String> getErrors() {
        return errors;
    }
}
