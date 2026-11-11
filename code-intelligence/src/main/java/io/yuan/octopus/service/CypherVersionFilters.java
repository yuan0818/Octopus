package io.yuan.octopus.service;

/**
 * MethodNode 的分支 / 版本过滤谓词，供 QueryService、PathQueryService 等共用。
 * <p>
 * 语义（全局一致）：
 * <ul>
 *   <li>branch 为空：不限分支；否则 {@code n.branch = $branch}</li>
 *   <li>commitId 为空：取当前版本 {@code coalesce(n.current, true) = true}；否则 {@code n.commitId = $commitId}</li>
 * </ul>
 * 两种生成方式：
 * <ul>
 *   <li>{@link #branch}/{@link #version}/{@link #node}：按入参是否为空生成不同 Cypher 片段，
 *       调用方只在非空时传 {@code $branch}/{@code $commitId} 参数。</li>
 *   <li>{@link #parameterized}：固定 Cypher 文本（利于查询计划缓存），调用方必须总是传
 *       {@code $projectId}（-1 表示不限）、{@code $branch}（'' 表示不限）、{@code $commitId}（'' 表示当前版本）。</li>
 * </ul>
 * 从 QueryService / PathQueryService 中抽出（M6 低风险拆分的第一步）。
 */
public final class CypherVersionFilters {

    private CypherVersionFilters() {
    }

    public static boolean hasBranch(String branch) {
        return branch != null && !branch.isBlank();
    }

    public static boolean isVersioned(String commitId) {
        return commitId != null && !commitId.isBlank();
    }

    public static String branch(String alias, String branch) {
        return hasBranch(branch) ? alias + ".branch = $branch" : "true";
    }

    public static String version(String alias, String commitId) {
        return isVersioned(commitId) ? alias + ".commitId = $commitId" : "coalesce(" + alias + ".current, true) = true";
    }

    /** {@code (branch AND version)} 组合片段。 */
    public static String node(String alias, String branch, String commitId) {
        return "(" + branch(alias, branch) + " AND " + version(alias, commitId) + ")";
    }

    /** 固定文本版本（含 projectId），见类注释。 */
    public static String parameterized(String alias) {
        return """
                ($projectId = -1 OR %s.projectId = $projectId)
                AND ($branch = '' OR %s.branch = $branch)
                AND (($commitId = '' AND coalesce(%s.current, true) = true)
                     OR ($commitId <> '' AND %s.commitId = $commitId))""".formatted(alias, alias, alias, alias);
    }
}
