package io.yuan.octopus.utils;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
@Deprecated
public class GitUrlValidator {

    // HTTP/HTTPS: https://host/path or https://host/path.git
    private static final Pattern HTTP_PATTERN = Pattern.compile(
            "^https?://[^/\\s]+/[^\\s]+"
    );

    // SSH: git@host:path.git or ssh://user@host/path.git
    private static final Pattern SSH_SCP_PATTERN = Pattern.compile(
            "^[\\w.-]+@[\\w.-]+:[^\\s]+"
    );

    private static final Pattern SSH_URL_PATTERN = Pattern.compile(
            "^ssh://[\\w.-]+@[\\w.-]+(:\\d+)?/[^\\s]+"
    );

    public record ValidationResult(boolean valid, String errorMessage) {
        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }
    }

    public ValidationResult validate(String url) {
        if (url == null || url.isBlank()) {
            return ValidationResult.failure("Repository URL must not be empty");
        }

        if (HTTP_PATTERN.matcher(url).matches()) {
            return ValidationResult.success();
        }

        if (SSH_SCP_PATTERN.matcher(url).matches()) {
            return ValidationResult.success();
        }

        if (SSH_URL_PATTERN.matcher(url).matches()) {
            return ValidationResult.success();
        }

        return ValidationResult.failure(
                "Invalid Git repository URL format. Expected HTTP(S) URL (e.g. https://github.com/user/repo.git) "
                        + "or SSH URL (e.g. git@github.com:user/repo.git or ssh://git@host/repo.git)"
        );
    }
}
