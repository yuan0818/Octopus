package io.yuan.octopus.scenario.testing;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

/** SO-010：格式无效事件写入建议度量，kind 带 _FORMAT 后缀，refId 为提示版本。 */
class FormatInvalidMetricTest {
    @Test
    void formatInvalidResponsesBecomeSuggestionMetrics() {
        var metrics = mock(SuggestionMetricsService.class);
        var client = new FlowModelClient("http://127.0.0.1:1");
        client.recordFormatInvalid("CASE_PLAN", "m", "case-plan@1");   // 未注入度量时不报错
        client.setMetrics(metrics);
        client.recordFormatInvalid("CASE_PLAN", "qwen-schema", "case-plan@1+schema@1");
        verify(metrics).record("CASE_PLAN_FORMAT", "FAILED", "qwen-schema", "case-plan@1+schema@1", null, null, null, "FORMAT_INVALID");
        verifyNoMoreInteractions(metrics);
    }
}
