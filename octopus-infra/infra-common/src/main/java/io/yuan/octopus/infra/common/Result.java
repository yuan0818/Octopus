package io.yuan.octopus.infra.common;

/**
 * Generic API response wrapper used by all REST endpoints.
 *
 * @param <T> the type of the payload carried by {@code data}
 */
public record Result<T>(Integer code, String message, Boolean success, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", true, data);
    }

    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, false, null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, false, null);
    }
}
