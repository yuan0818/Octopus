package io.yuan.octopus.changereview.controller;

import java.net.URI;

/**
 * 从仓库 URL 中提取 Gerrit project 名，用于 webhook 精确匹配 RepoConfig。
 * <p>
 * 之前按 {@code repoUrl LIKE %project%} 子串匹配，{@code core} 会同时命中
 * {@code platform-core}、{@code core-utils}，再配合无序的 {@code findFirst()}，
 * 可能拿错误仓库的 secret 校验 token（合法事件被拒），或把任务挂到错误仓库。
 * <p>
 * 支持的 URL 形态：
 * <ul>
 *   <li>{@code https://gerrit.example.com/a/group/project}（{@code /a/} 为 Gerrit 认证前缀）</li>
 *   <li>{@code https://gerrit.example.com/group/project.git}</li>
 *   <li>{@code ssh://user@gerrit.example.com:29418/group/project}</li>
 *   <li>{@code user@gerrit.example.com:group/project}（scp 风格）</li>
 * </ul>
 */
final class GerritProjectNames {

    private GerritProjectNames() {
    }

    /** 提取 project 名；无法解析时返回 null。 */
    static String projectOf(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return null;
        }
        String url = repoUrl.trim();
        String path;
        if (url.contains("://")) {
            try {
                path = URI.create(url).getPath();
            } catch (IllegalArgumentException e) {
                return null;
            }
        } else {
            int colon = url.indexOf(':');
            path = colon >= 0 ? url.substring(colon + 1) : url;   // scp 风格 user@host:path
        }
        if (path == null) {
            return null;
        }
        path = normalize(path);
        if (path.startsWith("a/")) {
            path = path.substring(2);
        }
        return path.isEmpty() ? null : path;
    }

    /** repoUrl 对应的 project 是否与 webhook payload 中的 project 完全一致。 */
    static boolean matches(String repoUrl, String project) {
        String expected = project == null ? null : normalize(project);
        return expected != null && !expected.isEmpty() && expected.equals(projectOf(repoUrl));
    }

    private static String normalize(String s) {
        String p = s.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.endsWith(".git")) p = p.substring(0, p.length() - 4);
        return p;
    }
}
