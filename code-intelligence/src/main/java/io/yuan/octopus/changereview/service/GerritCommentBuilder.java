package io.yuan.octopus.changereview.service;

import io.yuan.octopus.changereview.dto.AffectedEndpoint;
import io.yuan.octopus.changereview.dto.ChangedMethod;
import io.yuan.octopus.changereview.dto.EndpointReviewResult;
import io.yuan.octopus.changereview.dto.ReviewIssue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds the Gerrit change-level review comment (REQ-CTR-008). Groups findings by
 * affected endpoint, orders endpoints by severity, and truncates to a configurable
 * Top N (BR-CTR-040). All affected endpoints remain queryable in Octopus (the
 * comment points to the detail URL when truncated, BR-CTR-022).
 */
@Component
public class GerritCommentBuilder {

    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "BLOCKER", 0, "CRITICAL", 1, "WARNING", 2, "INFO", 3);

    /** Gerrit 单条评论长度上限（超长会被服务端拒绝），预留截断保护。 */
    private static final int MAX_COMMENT_LENGTH = 16000;

    private final int defaultTopN;

    public GerritCommentBuilder(@Value("${octopus.change-review.comment-top-n:10}") int defaultTopN) {
        this.defaultTopN = defaultTopN;
    }

    /**
     * @param endpoints      affected endpoints (with impact paths)
     * @param results        per-endpoint review results keyed by endpoint signature
     * @param deletedMethods deleted methods (reported, not reviewed)
     * @param octopusBaseUrl Octopus base URL for the detail link
     * @param taskId         change_review_task id (for the detail link)
     */
    public String build(List<AffectedEndpoint> endpoints,
                        Map<String, EndpointReviewResult> results,
                        List<ChangedMethod> deletedMethods,
                        String octopusBaseUrl, Long taskId) {
        int topN = defaultTopN;
        StringBuilder sb = new StringBuilder();
        sb.append("## Octopus 变更审查报告\n\n");

        if (endpoints.isEmpty() && deletedMethods.isEmpty()) {
            sb.append("无 Java 代码变更，跳过审查。\n");
            return sb.toString();
        }

        // Rank endpoints by their highest finding severity (lower rank = more severe).
        List<AffectedEndpoint> ranked = endpoints.stream()
                .sorted(Comparator.comparingInt(e -> severityRank(endpointTopSeverity(e.signature(), results))))
                .collect(Collectors.toList());

        int total = ranked.size();
        boolean truncated = total > topN;
        List<AffectedEndpoint> shown = ranked.subList(0, Math.min(topN, total));

        sb.append("**受影响端点：** ").append(total).append(" 个\n\n");

        for (AffectedEndpoint ep : shown) {
            EndpointReviewResult result = results.get(ep.signature());
            sb.append("### ").append(ep.httpMethod() != null ? ep.httpMethod() : "")
                    .append(" ").append(ep.httpPath() != null ? ep.httpPath() : "")
                    .append(" — `").append(ep.qualifiedClassName()).append("#").append(ep.methodName()).append("`\n");
            if (result == null) {
                sb.append("- ⚠️ 审查未执行\n");
            } else if (result.failed()) {
                sb.append("- ⚠️ 审查失败：").append(result.errorMessage()).append("\n");
            } else if (result.issues() != null && !result.issues().isEmpty()) {
                for (ReviewIssue issue : result.issues()) {
                    sb.append("- [").append(safe(issue.severity())).append("] ")
                            .append(safe(issue.category())).append(": ")
                            .append(safe(issue.description())).append("\n");
                }
            } else {
                sb.append("- ✅ 无问题\n");
            }
            if (ep.changedMethodsReached() != null && !ep.changedMethodsReached().isEmpty()) {
                sb.append("  影响路径到达变更方法：")
                        .append(String.join(", ", ep.changedMethodsReached())).append("\n");
            }
            sb.append("\n");
        }

        if (truncated) {
            sb.append("---\n共 ").append(total).append(" 个受影响端点，此处展示前 ").append(topN)
                    .append("。完整结果见：").append(detailLink(octopusBaseUrl, taskId)).append("\n\n");
        }

        if (deletedMethods != null && !deletedMethods.isEmpty()) {
            sb.append("**已删除方法（未作审查起点）：**\n");
            for (ChangedMethod dm : deletedMethods) {
                sb.append("- ").append(dm.qualifiedClassName() != null ? dm.qualifiedClassName() : "(unknown)")
                        .append("#").append(dm.methodName()).append("\n");
            }
        }

        // Gerrit 单条评论有长度上限，超长会被拒绝；截断并指向详情页
        if (sb.length() > MAX_COMMENT_LENGTH) {
            sb.setLength(MAX_COMMENT_LENGTH);
            sb.append("\n\n---\n评论已达长度上限被截断，完整结果见：")
                    .append(detailLink(octopusBaseUrl, taskId)).append("\n");
        }
        return sb.toString();
    }

    private String detailLink(String octopusBaseUrl, Long taskId) {
        String base = octopusBaseUrl != null ? octopusBaseUrl.replaceAll("/+$", "") : "";
        return base + "/api/change-review/" + taskId;
    }

    private String endpointTopSeverity(String sig, Map<String, EndpointReviewResult> results) {
        EndpointReviewResult r = results.get(sig);
        if (r == null) return "INFO";
        if (r.topSeverity() != null && !r.topSeverity().isBlank()) return r.topSeverity();
        // derive from issues
        int best = 3;
        if (r.issues() != null) {
            for (ReviewIssue i : r.issues()) {
                best = Math.min(best, severityRank(i.severity()));
            }
        }
        for (Map.Entry<String, Integer> e : SEVERITY_RANK.entrySet()) {
            if (e.getValue() == best) return e.getKey();
        }
        return "INFO";
    }

    private int severityRank(String severity) {
        if (severity == null) return 3;
        return SEVERITY_RANK.getOrDefault(severity.toUpperCase(java.util.Locale.ROOT), 3);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
