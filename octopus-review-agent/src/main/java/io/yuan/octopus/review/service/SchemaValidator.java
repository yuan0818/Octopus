package io.yuan.octopus.review.service;

import io.yuan.octopus.review.dto.CodeIssue;
import io.yuan.octopus.review.dto.ReviewResponse;
import io.yuan.octopus.review.dto.ReviewSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对 {@link ReviewResultParser} 解析后的 {@link ReviewResponse} 进行结构化校验。
 * <p>
 * 校验内容包括：
 * <ul>
 *   <li>必填字段完整性（issues 字段存在、每条 issue 的 severity/category/description）</li>
 *   <li>枚举值合法性（severity 在 BLOCKER/CRITICAL/WARNING/INFO 范围内）</li>
 * </ul>
 * <p>
 * 注意：{@code issues = []}（空数组、审查通过无问题）是<b>合法</b>结果，不做失败处理。
 * <p>
 * 校验失败时抛 {@link SchemaValidationException}，供 {@link SkillExecutor} 捕获并触发重试。
 */
@Component
public class SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidator.class);

    private static final Set<String> VALID_SEVERITIES = Set.of("BLOCKER", "CRITICAL", "WARNING", "INFO");

    /** 兼容映射：LLM 可能返回 HIGH/MEDIUM/LOW → 映射为标准值。 */
    private static final java.util.Map<String, String> SEVERITY_ALIASES = java.util.Map.of(
            "HIGH", "BLOCKER",
            "MEDIUM", "WARNING",
            "LOW", "INFO"
    );

    /**
     * 校验 ReviewResponse 的结构完整性。
     *
     * @param response 解析后的审查响应
     * @throws SchemaValidationException 校验失败时抛出
     */
    public void validate(ReviewResponse response) throws SchemaValidationException {
        List<String> errors = new ArrayList<>();

        if (response == null) {
            throw new SchemaValidationException("ReviewResponse 为 null", List.of("response is null"));
        }

        // 1. issues 检查：字段必须存在（非 null），但空数组合法——"审查通过、无问题"是正常结论
        if (response.getIssues() == null) {
            errors.add("缺失 issues 列表（null）");
        } else {
            for (int i = 0; i < response.getIssues().size(); i++) {
                CodeIssue issue = response.getIssues().get(i);
                validateIssue(i, issue, errors);
            }
        }

        // 2. requirementChecks 检查（REQUIREMENT_AWARE 模式）
        List<io.yuan.octopus.review.dto.RequirementCheck> checks = response.getRequirementChecks();
        if (checks != null) {
            for (int i = 0; i < checks.size(); i++) {
                var check = checks.get(i);
                if (check.getRequirementId() == null || check.getRequirementId().isBlank()) {
                    errors.add("requirementChecks[" + i + "]: 缺失 requirementId");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new SchemaValidationException("LLM 响应校验失败", errors);
        }

        log.debug("Schema validation passed: {} issues, {} checks",
                response.getIssues() != null ? response.getIssues().size() : 0,
                checks != null ? checks.size() : 0);
    }

    private void validateIssue(int index, CodeIssue issue, List<String> errors) {
        String prefix = "issues[" + index + "]: ";

        // severity 必填 + 枚举
        String severity = issue.getSeverity();
        if (severity == null || severity.isBlank()) {
            errors.add(prefix + "缺失 severity");
        } else {
            String upper = severity.toUpperCase(java.util.Locale.ROOT);
            if (!VALID_SEVERITIES.contains(upper)) {
                // 尝试兼容映射
                String mapped = SEVERITY_ALIASES.get(upper);
                if (mapped != null) {
                    issue.setSeverity(mapped);  // 自动修正兼容值
                } else {
                    errors.add(prefix + "severity '" + severity + "' 不在合法集合 [BLOCKER, CRITICAL, WARNING, INFO] 中");
                }
            }
        }

        // category 非空
        if (issue.getCategory() == null || issue.getCategory().isBlank()) {
            errors.add(prefix + "缺失 category");
        }

        // description 非空
        if (issue.getDescription() == null || issue.getDescription().isBlank()) {
            errors.add(prefix + "缺失 description");
        }
    }
}
