package io.yuan.octopus.reconstruct.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitSourceProviderTest {

    private GitSourceProvider provider;

    @BeforeEach
    void setUp() {
        // 使用不存在的路径，仅测试纯逻辑方法
        provider = new GitSourceProvider("/tmp/nonexistent-repos", null);
    }

    // ── extractLines 正常行号提取 ────────────────────────────────

    @Test
    void shouldExtractMiddleLines() {
        String content = "line1\nline2\nline3\nline4\nline5";
        String result = provider.extractLines(content, 2, 4);
        assertEquals("line2\nline3\nline4", result);
    }

    @Test
    void shouldExtractSingleLine() {
        String content = "line1\nline2\nline3";
        String result = provider.extractLines(content, 2, 2);
        assertEquals("line2", result);
    }

    @Test
    void shouldExtractAllLines() {
        String content = "line1\nline2\nline3";
        String result = provider.extractLines(content, 1, 3);
        assertEquals("line1\nline2\nline3", result);
    }

    // ── extractLines 边界行号 ────────────────────────────────────

    @Test
    void shouldHandleLineStartZero() {
        String content = "line1\nline2\nline3";
        // lineStart <= 0 被修正为 1
        String result = provider.extractLines(content, 0, 2);
        assertEquals("line1\nline2", result);
    }

    @Test
    void shouldHandleLineEndBeyondContent() {
        String content = "line1\nline2";
        // lineEnd 超出内容行数，安全截断
        String result = provider.extractLines(content, 1, 100);
        assertEquals("line1\nline2", result);
    }

    @Test
    void shouldHandleEmptyContent() {
        String result = provider.extractLines("", 1, 5);
        assertEquals("", result);
    }

    @Test
    void shouldHandleLineStartGreaterThanContent() {
        String content = "line1\nline2";
        // lineStart-1 = 10 > lines.length(2), startIdx = 2, endIdx = 2 → 空
        String result = provider.extractLines(content, 10, 20);
        assertEquals("", result);
    }

    // ── extractRepoName 从各种 URL 格式提取仓库名 ────────────────

    @Test
    void shouldExtractFromHttpsGitUrl() {
        assertEquals("my-app", GitSourceProvider.extractRepoName("https://github.com/org/my-app.git"));
    }

    @Test
    void shouldExtractFromHttpsUrlWithoutGitSuffix() {
        assertEquals("my-app", GitSourceProvider.extractRepoName("https://github.com/org/my-app"));
    }

    @Test
    void shouldExtractFromSshUrl() {
        assertEquals("my-app", GitSourceProvider.extractRepoName("git@github.com:org/my-app.git"));
    }

    @Test
    void shouldExtractFromSshUrlWithoutGitSuffix() {
        assertEquals("my-app", GitSourceProvider.extractRepoName("git@github.com:org/my-app"));
    }

    @Test
    void shouldExtractFromHttpUrl() {
        assertEquals("project-x", GitSourceProvider.extractRepoName("http://gitlab.local/group/project-x.git"));
    }

    @Test
    void shouldHandleTrailingSlash() {
        assertEquals("my-app", GitSourceProvider.extractRepoName("https://github.com/org/my-app/"));
    }

    @Test
    void shouldReturnNullForNullInput() {
        assertNull(GitSourceProvider.extractRepoName(null));
    }

    @Test
    void shouldReturnNullForBlankInput() {
        assertNull(GitSourceProvider.extractRepoName("   "));
    }

    @Test
    void shouldHandleSimpleRepoName() {
        assertEquals("my-repo", GitSourceProvider.extractRepoName("my-repo"));
    }

    // ── getSource 参数校验 ────────────────────────────────────────

    @Test
    void shouldReturnNotFoundWhenCommitIdIsNull() {
        SourceResult result = provider.getSource(1L, null, "src/Foo.java", 1, 10);
        assertFalse(result.found());
        assertTrue(result.errorMsg().contains("commitId"));
    }

    @Test
    void shouldReturnNotFoundWhenCommitIdIsBlank() {
        SourceResult result = provider.getSource(1L, "   ", "src/Foo.java", 1, 10);
        assertFalse(result.found());
        assertTrue(result.errorMsg().contains("commitId"));
    }

    @Test
    void shouldReturnNotFoundWhenFilePathIsNull() {
        SourceResult result = provider.getSource(1L, "abc123", null, 1, 10);
        assertFalse(result.found());
        assertTrue(result.errorMsg().contains("filePath"));
    }

    @Test
    void shouldReturnNotFoundWhenFilePathIsBlank() {
        SourceResult result = provider.getSource(1L, "abc123", "   ", 1, 10);
        assertFalse(result.found());
        assertTrue(result.errorMsg().contains("filePath"));
    }

    // ── SourceResult 工厂方法 ──────────────────────────────────────

    @Test
    void sourceResultFoundShouldHaveContent() {
        SourceResult result = SourceResult.found("some code");
        assertTrue(result.found());
        assertEquals("some code", result.content());
        assertNull(result.errorMsg());
    }

    @Test
    void sourceResultNotFoundShouldHaveErrorMsg() {
        SourceResult result = SourceResult.notFound("not here");
        assertFalse(result.found());
        assertNull(result.content());
        assertEquals("not here", result.errorMsg());
    }

    @Test
    void sourceResultErrorShouldHaveErrorMsg() {
        SourceResult result = SourceResult.error("failed");
        assertFalse(result.found());
        assertNull(result.content());
        assertEquals("failed", result.errorMsg());
    }

    // ── relativizeFilePath 路径相对化 ─────────────────────────────

    @Test
    void shouldRelativizeAbsolutePathToRepoRoot() {
        // 绝对路径 → 相对仓库根
        String result = GitSourceProvider.relativizeFilePath(
                "/data/repos/my-app/src/main/java/com/x/Foo.java",
                "/data/repos/my-app");
        assertEquals("src/main/java/com/x/Foo.java", result);
    }

    @Test
    void shouldKeepRelativePathAsIs() {
        // 已经是相对路径 → 原样返回
        String result = GitSourceProvider.relativizeFilePath(
                "src/main/java/com/x/Foo.java",
                "/data/repos/my-app");
        assertEquals("src/main/java/com/x/Foo.java", result);
    }

    @Test
    void shouldReturnAsIsWhenRepoWorkTreeIsNull() {
        // 仓库根为 null → 绝对路径原样返回（统一分隔符）
        String result = GitSourceProvider.relativizeFilePath(
                "/data/repos/my-app/src/Foo.java",
                null);
        assertEquals("/data/repos/my-app/src/Foo.java", result);
    }

    @Test
    void shouldReturnAsIsWhenPathOutsideRepo() {
        // 路径在仓库外 → 原样返回（不强行相对化）
        String result = GitSourceProvider.relativizeFilePath(
                "/other/location/Foo.java",
                "/data/repos/my-app");
        assertEquals("/other/location/Foo.java", result);
    }

    @Test
    void shouldNormalizeBackslashToSlash() {
        // Windows 风格路径 → 统一为正斜杠
        String result = GitSourceProvider.relativizeFilePath(
                "src\\main\\java\\Foo.java",
                "/data/repos/my-app");
        assertEquals("src/main/java/Foo.java", result);
    }

    @Test
    void shouldHandleNestedRepoPath() {
        // 多层目录的相对化
        String result = GitSourceProvider.relativizeFilePath(
                "/workspace/projects/repo/module/src/main/java/Bar.java",
                "/workspace/projects/repo");
        assertEquals("module/src/main/java/Bar.java", result);
    }
}
