package io.yuan.octopus.changereview.service;

import io.yuan.octopus.changereview.dto.AffectedEndpoint;
import io.yuan.octopus.changereview.dto.ChangedMethod;
import io.yuan.octopus.changereview.dto.EndpointReviewResult;
import io.yuan.octopus.changereview.dto.ReviewIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GerritCommentBuilderTest {

    private GerritCommentBuilder builder;

    private static final String OCTOPUS_URL = "http://localhost:8080";
    private static final Long TASK_ID = 1L;

    @BeforeEach
    void setUp() {
        builder = new GerritCommentBuilder(3); // small topN for truncation test
    }

    @Test
    void shouldBuildReportWithFindings() {
        AffectedEndpoint ep = new AffectedEndpoint(10L, "com.Ctrl", "get", "sig_ctrl",
                "GET", "/api/orders",
                List.of("sig_helper"), List.of(List.of("sig_ctrl", "sig_helper")));
        EndpointReviewResult result = new EndpointReviewResult("rev_1", "NORMAL", "CRITICAL",
                List.of(new ReviewIssue("CRITICAL", "SECURITY", "Missing auth check", "Add @PreAuthorize")),
                null);
        Map<String, EndpointReviewResult> results = Map.of("sig_ctrl", result);

        String body = builder.build(List.of(ep), results, List.of(), OCTOPUS_URL, TASK_ID);

        assertNotNull(body);
        assertTrue(body.contains("Octopus 变更审查报告"));
        assertTrue(body.contains("受影响端点：** 1 个"));
        assertTrue(body.contains("GET /api/orders"));
        assertTrue(body.contains("CRITICAL"));
        assertTrue(body.contains("Missing auth check"));
        assertTrue(body.contains("sig_helper")); // impact path
    }

    @Test
    void shouldShowCleanEndpoint() {
        AffectedEndpoint ep = new AffectedEndpoint(10L, "com.Ctrl", "get", "sig_ctrl",
                "GET", "/api/orders", List.of(), List.of());
        EndpointReviewResult result = new EndpointReviewResult("rev_1", "NORMAL", null,
                List.of(), null);
        Map<String, EndpointReviewResult> results = Map.of("sig_ctrl", result);

        String body = builder.build(List.of(ep), results, List.of(), OCTOPUS_URL, TASK_ID);
        assertTrue(body.contains("✅ 无问题"));
    }

    @Test
    void shouldTruncateTopN() {
        List<AffectedEndpoint> eps = List.of(
                ep("sig_crit", "CRITICAL"),
                ep("sig_warn", "WARNING"),
                ep("sig_info1", "INFO"),
                ep("sig_info2", "INFO"));
        Map<String, EndpointReviewResult> results = Map.of(
                "sig_crit", result("CRITICAL"),
                "sig_warn", result("WARNING"),
                "sig_info1", result("INFO"),
                "sig_info2", result("INFO"));

        String body = builder.build(eps, results, List.of(), OCTOPUS_URL, TASK_ID);
        assertTrue(body.contains("共 4 个受影响端点，此处展示前 3"));
        assertTrue(body.contains("sig_crit"));
        assertTrue(body.contains("sig_warn"));
        assertTrue(body.contains("sig_info1"));
        assertFalse(body.contains("sig_info2"));
    }

    @Test
    void shouldIncludeDetailLinkWhenTruncated() {
        List<AffectedEndpoint> eps = List.of(ep("a", "INFO"), ep("b", "INFO"), ep("c", "INFO"), ep("d", "INFO"));
        Map<String, EndpointReviewResult> results = Map.of(
                "a", result("INFO"), "b", result("INFO"), "c", result("INFO"), "d", result("INFO"));
        String body = builder.build(eps, results, List.of(), OCTOPUS_URL, TASK_ID);
        assertTrue(body.contains(OCTOPUS_URL + "/api/change-review/" + TASK_ID));
    }

    @Test
    void shouldHandleEmptyEndpoints() {
        String body = builder.build(List.of(), Map.of(), List.of(), OCTOPUS_URL, TASK_ID);
        assertTrue(body.contains("无 Java 代码变更"));
    }

    @Test
    void shouldListDeletedMethods() {
        ChangedMethod deleted = new ChangedMethod(10L, "com.Old", "oldMethod",
                "com.Old.oldMethod()", "/abs/Old.java", "DELETED");
        String body = builder.build(List.of(), Map.of(), List.of(deleted), OCTOPUS_URL, TASK_ID);
        assertTrue(body.contains("已删除方法"));
        assertTrue(body.contains("com.Old#oldMethod"));
        // deleted methods are reported — "no changes" is NOT shown since there ARE changes (deletions).
    }

    @Test
    void shouldShowFailedEndpoint() {
        AffectedEndpoint ep = ep("sig_fail", "INFO");
        EndpointReviewResult failed = new EndpointReviewResult(null, "FAILED", null, List.of(),
                "Review agent unavailable");
        Map<String, EndpointReviewResult> results = Map.of("sig_fail", failed);
        String body = builder.build(List.of(ep), results, List.of(), OCTOPUS_URL, TASK_ID);
        assertTrue(body.contains("审查失败：Review agent unavailable"));
    }

    // ── helpers ──

    private AffectedEndpoint ep(String sig, String topSev) {
        return new AffectedEndpoint(10L, "com.Ctrl", "m_" + sig, sig,
                "GET", "/api/" + sig, List.of(), List.of());
    }

    private EndpointReviewResult result(String topSeverity) {
        return new EndpointReviewResult("rev", "NORMAL", topSeverity,
                topSeverity.equals("INFO") ? List.of()
                        : List.of(new ReviewIssue(topSeverity, "CAT", "desc", "sugg")),
                null);
    }
}
