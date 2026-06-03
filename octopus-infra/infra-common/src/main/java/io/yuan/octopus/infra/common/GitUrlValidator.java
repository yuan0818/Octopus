package io.yuan.octopus.infra.common;

import java.util.regex.Pattern;

/**
 * Git URL format validator.
 */
public class GitUrlValidator {

    private static final Pattern HTTP_PATTERN =
            Pattern.compile("^https?://[^/\\s]+/[^\\s]+");
    private static final Pattern SSH_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9._-]*@[a-zA-Z0-9._-]+:.*");
    private static final Pattern SCP_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9._-]+:[^\\s]+");

    public static boolean isValid(String url) {
        if (url == null || url.isBlank()) return false;
        return HTTP_PATTERN.matcher(url).matches()
                || SSH_PATTERN.matcher(url).matches()
                || SCP_PATTERN.matcher(url).matches();
    }
}
