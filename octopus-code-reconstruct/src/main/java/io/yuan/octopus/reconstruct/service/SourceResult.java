package io.yuan.octopus.reconstruct.service;

/**
 * JGit git show 操作的结果。
 *
 * @param found     是否成功找到并读取文件内容
 * @param content   方法体源码字符串（found=true 时有效）
 * @param errorMsg  错误描述（found=false 时有效）
 */
public record SourceResult(
        boolean found,
        String content,
        String errorMsg
) {
    public static SourceResult found(String content) {
        return new SourceResult(true, content, null);
    }

    public static SourceResult notFound(String msg) {
        return new SourceResult(false, null, msg);
    }

    public static SourceResult error(String msg) {
        return new SourceResult(false, null, msg);
    }
}
