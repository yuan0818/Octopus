package io.yuan.octopus.review.service;

import io.yuan.octopus.review.dto.CodeIssue;
import io.yuan.octopus.review.dto.RequirementCheck;
import io.yuan.octopus.review.dto.ReviewResponse;
import io.yuan.octopus.review.dto.ReviewSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaValidatorTest {

    private SchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SchemaValidator();
    }

    // ── 正常校验通过 ──────────────────────────────────────────────

    @Test
    void shouldPassWhenAllFieldsValid() {
        ReviewResponse response = validResponse();
        assertDoesNotThrow(() -> validator.validate(response));
    }

    // ── issues 为空 / null ───────────────────────────────────────

    @Test
    void shouldFailWhenIssuesIsNull() {
        ReviewResponse response = validResponse();
        response.setIssues(null);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> validator.validate(response));
        assertTrue(ex.getMessage().contains("issues"));
    }

    @Test
    void shouldPassWhenIssuesIsEmpty() {
        // "审查通过、无问题"（issues=[]）是合法结论，不应触发重试/降级
        ReviewResponse response = validResponse();
        response.setIssues(List.of());
        assertDoesNotThrow(() -> validator.validate(response));
    }

    // ── severity 校验 ────────────────────────────────────────────

    @Test
    void shouldFailWhenSeverityIsMissing() {
        CodeIssue issue = new CodeIssue();
        issue.setSeverity(null);
        issue.setCategory("SECURITY");
        issue.setDescription("test");
        ReviewResponse response = responseWithIssues(issue);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> validator.validate(response));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("severity")));
    }

    @Test
    void shouldFailWhenSeverityIsInvalid() {
        CodeIssue issue = new CodeIssue();
        issue.setSeverity("UNKNOWN");
        issue.setCategory("SECURITY");
        issue.setDescription("test");
        ReviewResponse response = responseWithIssues(issue);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> validator.validate(response));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("UNKNOWN")));
    }

    @Test
    void shouldMapHighToBlocker() {
        CodeIssue issue = new CodeIssue();
        issue.setSeverity("HIGH");
        issue.setCategory("SECURITY");
        issue.setDescription("test");
        ReviewResponse response = responseWithIssues(issue);
        assertDoesNotThrow(() -> validator.validate(response));
        assertEquals("BLOCKER", issue.getSeverity());
    }

    @Test
    void shouldMapMediumToWarning() {
        CodeIssue issue = new CodeIssue();
        issue.setSeverity("MEDIUM");
        issue.setCategory("SECURITY");
        issue.setDescription("test");
        ReviewResponse response = responseWithIssues(issue);
        assertDoesNotThrow(() -> validator.validate(response));
        assertEquals("WARNING", issue.getSeverity());
    }

    @Test
    void shouldMapLowToInfo() {
        CodeIssue issue = new CodeIssue();
        issue.setSeverity("LOW");
        issue.setCategory("SECURITY");
        issue.setDescription("test");
        ReviewResponse response = responseWithIssues(issue);
        assertDoesNotThrow(() -> validator.validate(response));
        assertEquals("INFO", issue.getSeverity());
    }

    // ── category / description 校验 ──────────────────────────────

    @Test
    void shouldFailWhenCategoryIsMissing() {
        CodeIssue issue = new CodeIssue();
        issue.setSeverity("BLOCKER");
        issue.setCategory(null);
        issue.setDescription("test");
        ReviewResponse response = responseWithIssues(issue);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> validator.validate(response));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("category")));
    }

    @Test
    void shouldFailWhenDescriptionIsMissing() {
        CodeIssue issue = new CodeIssue();
        issue.setSeverity("BLOCKER");
        issue.setCategory("SECURITY");
        issue.setDescription(null);
        ReviewResponse response = responseWithIssues(issue);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> validator.validate(response));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("description")));
    }

    @Test
    void shouldFailWhenDescriptionIsBlank() {
        CodeIssue issue = new CodeIssue();
        issue.setSeverity("BLOCKER");
        issue.setCategory("SECURITY");
        issue.setDescription("   ");
        ReviewResponse response = responseWithIssues(issue);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> validator.validate(response));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("description")));
    }

    // ── requirementChecks 校验 ───────────────────────────────────

    @Test
    void shouldFailWhenRequirementCheckMissingId() {
        ReviewResponse response = validResponse();
        RequirementCheck check = new RequirementCheck();
        check.setRequirementId(null);
        response.setRequirementChecks(List.of(check));
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> validator.validate(response));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("requirementId")));
    }

    @Test
    void shouldPassWhenRequirementChecksIsNull() {
        ReviewResponse response = validResponse();
        response.setRequirementChecks(null);
        assertDoesNotThrow(() -> validator.validate(response));
    }

    @Test
    void shouldPassWhenRequirementChecksHaveValidId() {
        ReviewResponse response = validResponse();
        RequirementCheck check = new RequirementCheck();
        check.setRequirementId("REQ-001");
        response.setRequirementChecks(List.of(check));
        assertDoesNotThrow(() -> validator.validate(response));
    }

    // ── response null ────────────────────────────────────────────

    @Test
    void shouldFailWhenResponseIsNull() {
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> validator.validate(null));
        assertTrue(ex.getMessage().contains("null"));
    }

    // ── 合法 severity 枚举值 ─────────────────────────────────────

    @Test
    void shouldAcceptAllValidSeverities() {
        for (String severity : List.of("BLOCKER", "CRITICAL", "WARNING", "INFO")) {
            CodeIssue issue = new CodeIssue();
            issue.setSeverity(severity);
            issue.setCategory("SECURITY");
            issue.setDescription("test");
            ReviewResponse response = responseWithIssues(issue);
            assertDoesNotThrow(() -> validator.validate(response),
                    "Severity '" + severity + "' should be valid");
        }
    }

    // ── 多条 issues 中部分校验失败 ───────────────────────────────

    @Test
    void shouldReportMultipleErrorsForMultipleInvalidIssues() {
        CodeIssue issue1 = new CodeIssue();
        issue1.setSeverity(null);
        issue1.setCategory(null);
        issue1.setDescription(null);

        CodeIssue issue2 = new CodeIssue();
        issue2.setSeverity("INVALID");
        issue2.setCategory("SECURITY");
        issue2.setDescription("ok");

        ReviewResponse response = responseWithIssues(issue1, issue2);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> validator.validate(response));
        // issue1 缺少 severity, category, description；issue2 severity 不合法
        assertTrue(ex.getErrors().size() >= 4,
                "Expected at least 4 errors but got " + ex.getErrors().size());
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ReviewResponse validResponse() {
        ReviewResponse response = new ReviewResponse();
        CodeIssue issue = new CodeIssue();
        issue.setSeverity("BLOCKER");
        issue.setCategory("SECURITY");
        issue.setDescription("SQL injection risk");
        response.setIssues(List.of(issue));
        response.setSummary(new ReviewSummary(0, 0, 0, 1, "BLOCKER", "OK"));
        return response;
    }

    private ReviewResponse responseWithIssues(CodeIssue... issues) {
        ReviewResponse response = new ReviewResponse();
        response.setIssues(List.of(issues));
        return response;
    }
}
