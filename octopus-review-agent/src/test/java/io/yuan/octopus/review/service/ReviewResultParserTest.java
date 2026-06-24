package io.yuan.octopus.review.service;

import io.yuan.octopus.review.dto.ReviewResponse;
import io.yuan.octopus.review.dto.ReviewMode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReviewResultParserTest {

    private final ReviewResultParser parser = new ReviewResultParser();

    @Test
    void parseValidResponse() {
        String llmJson = """
                {
                  "requirementChecks": [
                    {
                      "requirementId": "REQ-001",
                      "passed": true,
                      "confidence": "HIGH",
                      "detail": "使用了 @Valid",
                      "codeReferences": ["Controller.java:25"],
                      "suggestion": null
                    }
                  ],
                  "issues": [
                    {
                      "severity": "BLOCKER",
                      "category": "SECURITY",
                      "description": "SQL注入风险",
                      "codeReferences": ["Dao.java:42"],
                      "suggestion": "使用参数化查询"
                    }
                  ]
                }
                """;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("anchor", "com.x.Controller.create");

        ReviewResponse response = parser.parse(llmJson, metadata);

        assertNotNull(response);
        assertEquals("com.x.Controller.create", response.getAnchor());
        assertEquals(1, response.getRequirementChecks().size());
        assertEquals("REQ-001", response.getRequirementChecks().get(0).getRequirementId());
        assertTrue(response.getRequirementChecks().get(0).isPassed());

        assertEquals(1, response.getIssues().size());
        assertEquals("BLOCKER", response.getIssues().get(0).getSeverity());
        assertEquals("SECURITY", response.getIssues().get(0).getCategory());

        assertNotNull(response.getSummary());
        assertEquals(1, response.getSummary().getTotalRequirements());
        assertEquals(1, response.getSummary().getPassed());
    }

    @Test
    void parseEmptyResponse() {
        String llmJson = "{}";

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("anchor", "test");

        ReviewResponse response = parser.parse(llmJson, metadata);

        assertNotNull(response);
        assertNotNull(response.getRequirementChecks());
        assertTrue(response.getRequirementChecks().isEmpty());
    }

    @Test
    void parseMalformedJson() {
        String llmJson = "这不是有效的JSON";

        ReviewResponse response = parser.parse(llmJson, Map.of("anchor", "test"));

        assertNotNull(response);
        assertNotNull(response.getRequirementChecks());
        assertEquals(0, response.getSummary().getTotalRequirements());
    }

    @Test
    void parseWithIssuesSortedBySeverity() {
        String llmJson = """
                {
                  "issues": [
                    {"severity": "WARNING", "category": "CONVENTION", "description": "小问题"},
                    {"severity": "BLOCKER", "category": "SECURITY", "description": "严重问题"},
                    {"severity": "CRITICAL", "category": "PERFORMANCE", "description": "中等问题"}
                  ]
                }
                """;

        ReviewResponse response = parser.parse(llmJson, Map.of("anchor", "test"));

        assertEquals(3, response.getIssues().size());
        assertEquals("BLOCKER", response.getIssues().get(0).getSeverity());
        assertEquals("CRITICAL", response.getIssues().get(1).getSeverity());
        assertEquals("WARNING", response.getIssues().get(2).getSeverity());
    }

    @Test
    void riskBasedModeDoesNotCreateRequirementChecksFromRequirementMismatch() {
        String llmJson = """
                [
                  {
                    "id": "REQ-001",
                    "title": "需求错配",
                    "category": "REQUIREMENT_MISMATCH",
                    "severity": "HIGH",
                    "confidence": "HIGH",
                    "summary": "无需求模式下不应生成需求检查",
                    "codeEvidence": [{"symbol": "Job#run", "snippet": "run();"}],
                    "suggestion": "N/A"
                  }
                ]
                """;

        ReviewResponse response = parser.parse(llmJson, Map.of("anchor", "test"), ReviewMode.RISK_BASED);

        assertEquals(ReviewMode.RISK_BASED, response.getReviewMode());
        assertEquals(1, response.getIssues().size());
        assertTrue(response.getRequirementChecks().isEmpty());
        assertEquals(0, response.getSummary().getTotalRequirements());
    }
}
