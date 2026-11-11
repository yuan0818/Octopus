package io.yuan.octopus.changereview.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 把 scenario-runner 批量回归的最终摘要渲染为 Gerrit 评论（Phase 1B）。
 * <p>
 * 只使用摘要中的计数、判定、测试点标识和失败原因；不包含请求/响应正文或任何凭据。
 */
@Component
public class RegressionCommentBuilder {

    static final int MAX_LISTED_ITEMS = 15;

    public String build(Map<String, Object> summary, String octopusBaseUrl) {
        StringBuilder sb = new StringBuilder();
        String verdict = str(summary.get("verdict"));
        Map<?, ?> counts = summary.get("counts") instanceof Map<?, ?> m ? m : Map.of();
        sb.append("Octopus 业务回归：").append(label(verdict));
        sb.append("（受影响测试点 ").append(num(counts.get("total")))
                .append("：通过 ").append(num(counts.get("pass")))
                .append(" · 失败 ").append(num(counts.get("fail")))
                .append(" · 无法判定 ").append(num(counts.get("inconclusive")))
                .append(" · 跳过 ").append(num(counts.get("skipped")))
                .append(" · 错误 ").append(num(counts.get("error"))).append("）\n");
        sb.append("环境 ").append(str(summary.get("environment")))
                .append(" · ").append(shortSha(summary.get("baseCommitId")))
                .append(" → ").append(shortSha(summary.get("targetCommitId"))).append('\n');

        if ("CANCELLED".equals(summary.get("status"))) sb.append("批次已被取消，结果不完整。\n");
        if (summary.get("error") != null) sb.append("批次错误：").append(str(summary.get("error"))).append('\n');
        if (summary.get("impact") instanceof Map<?, ?> impact) {
            Object status = impact.get("codeImpactStatus");
            if (status != null && !"STATIC_GRAPH".equals(status) && !"COMPLETE".equals(status)) {
                sb.append("⚠ 影响分析状态 ").append(status).append("：受影响范围可能不完整（请确认两个版本都已完成分析）。\n");
            }
            if (Boolean.TRUE.equals(impact.get("truncated"))) sb.append("⚠ 受影响测试点超过批次上限，仅执行了前一部分。\n");
            if (impact.get("unmappedEndpoints") instanceof List<?> unmapped && !unmapped.isEmpty()) {
                sb.append("未关联测试的变更入口 ").append(unmapped.size()).append(" 个（尚无对应 API 契约 / 测试流程）。\n");
            }
        }

        if (summary.get("items") instanceof List<?> items && !items.isEmpty()) {
            sb.append('\n');
            int listed = 0;
            // 先列出非通过项，便于定位
            for (int pass = 0; pass < 2; pass++) {
                for (Object raw : items) {
                    if (!(raw instanceof Map<?, ?> item)) continue;
                    boolean passed = "DONE".equals(item.get("state")) && "PASS".equals(item.get("verdict"));
                    if ((pass == 0) == passed) continue;
                    if (listed++ >= MAX_LISTED_ITEMS) break;
                    sb.append("- [").append(itemLabel(item)).append("] ")
                            .append(str(item.get("flowId"))).append('#').append(str(item.get("testPointId")));
                    if (item.get("reason") != null) sb.append(" — ").append(truncate(str(item.get("reason")), 160));
                    sb.append('\n');
                }
            }
            if (items.size() > MAX_LISTED_ITEMS) sb.append("… 共 ").append(items.size()).append(" 项，其余见 Octopus。\n");
        }
        sb.append("\n批次 ").append(str(summary.get("id")));
        if (octopusBaseUrl != null && !octopusBaseUrl.isBlank()) {
            sb.append(" · ").append(octopusBaseUrl.replaceFirst("/$", "")).append("/business-testing/regressions?id=").append(str(summary.get("id")));
        }
        return sb.toString();
    }

    private static String label(String verdict) {
        return switch (verdict) {
            case "PASS" -> "✅ 通过";
            case "FAIL" -> "❌ 失败";
            case "NO_AFFECTED_TESTS" -> "ℹ 无受影响的已确认测试";
            default -> "⚠ 无法判定";
        };
    }

    private static String itemLabel(Map<?, ?> item) {
        String state = str(item.get("state"));
        if ("DONE".equals(state)) return str(item.get("verdict"));
        return state;
    }

    private static String shortSha(Object sha) {
        String s = str(sha);
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static String num(Object value) {
        return value instanceof Number n ? String.valueOf(n.intValue()) : "0";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
