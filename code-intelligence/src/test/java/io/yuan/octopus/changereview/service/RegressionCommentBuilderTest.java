package io.yuan.octopus.changereview.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegressionCommentBuilderTest {

    private final RegressionCommentBuilder builder = new RegressionCommentBuilder();

    private static Map<String, Object> item(String point, String state, String verdict, String reason) {
        var m = new java.util.HashMap<String, Object>();
        m.put("flowId", "order"); m.put("testPointId", point); m.put("state", state); m.put("verdict", verdict); m.put("reason", reason);
        return m;
    }

    @Test
    void failuresAreListedBeforePasses() {
        Map<String, Object> summary = Map.of(
                "id", "b1", "status", "COMPLETED", "verdict", "FAIL", "environment", "test",
                "baseCommitId", "a".repeat(40), "targetCommitId", "b".repeat(40),
                "counts", Map.of("total", 3, "pass", 1, "fail", 1, "inconclusive", 0, "skipped", 1, "error", 0),
                "impact", Map.of("codeImpactStatus", "PARTIAL", "unmappedEndpoints", List.of("GET /x")),
                "items", List.of(item("ok", "DONE", "PASS", null), item("broken", "DONE", "FAIL", "Business verification failed: st"),
                        item("noenv", "SKIPPED", null, "call n1：未配置测试地址")));
        String body = builder.build(summary, "http://octopus.local/");
        assertTrue(body.startsWith("Octopus 业务回归：❌ 失败"));
        assertTrue(body.contains("aaaaaaaa → bbbbbbbb"));
        assertTrue(body.contains("影响分析状态 PARTIAL"));
        assertTrue(body.contains("未关联测试的变更入口 1 个"));
        assertTrue(body.indexOf("order#broken") < body.indexOf("order#ok"));
        assertTrue(body.contains("[SKIPPED] order#noenv — call n1：未配置测试地址"));
        assertTrue(body.contains("http://octopus.local/business-testing/regressions?id=b1"));
    }

    @Test
    void noAffectedTests() {
        String body = builder.build(Map.of("id", "b2", "verdict", "NO_AFFECTED_TESTS", "counts", Map.of("total", 0)), null);
        assertTrue(body.contains("无受影响的已确认测试"));
    }
}
