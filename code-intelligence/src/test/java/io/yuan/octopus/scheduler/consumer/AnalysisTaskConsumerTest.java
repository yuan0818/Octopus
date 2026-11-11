package io.yuan.octopus.scheduler.consumer;

import com.rabbitmq.client.Channel;
import io.yuan.octopus.infra.common.mq.AnalysisTaskMessage;
import io.yuan.octopus.service.AnalysisOrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskConsumerTest {

    @Mock
    private AnalysisOrchestrationService orchestrationService;
    @Mock
    private Channel channel;

    private AnalysisTaskConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AnalysisTaskConsumer(orchestrationService);
    }

    private AnalysisTaskMessage msg(Long repoId, String tag) {
        return new AnalysisTaskMessage(1L, repoId, null, null, null, tag, "JAVA", "FULL", null);
    }

    @Test
    void shouldStartAnalysisAndAck() throws Exception {
        consumer.handleJava(msg(5L, "v1.0"), channel, 10L, null);
        verify(orchestrationService).startAnalysis(5L, "v1.0");
        verify(channel).basicAck(10L, false);
    }

    @Test
    void shouldDropMessageWithoutRepoConfigIdOrTag() throws Exception {
        consumer.handleJava(msg(null, "v1.0"), channel, 11L, null);
        consumer.handleJava(msg(5L, null), channel, 12L, null);
        verify(orchestrationService, never()).startAnalysis(anyLong(), anyString());
        verify(channel).basicAck(11L, false);
        verify(channel).basicAck(12L, false);
    }

    @Test
    void shouldDropAfterRetriesExhausted() throws Exception {
        List<Map<String, ?>> xDeath = List.of(Map.of("count", 6L));
        consumer.handleGo(msg(5L, "v2.0"), channel, 13L, xDeath);
        verify(orchestrationService, never()).startAnalysis(anyLong(), anyString());
        verify(channel).basicAck(13L, false);
    }

    @Test
    void shouldNackForRetryOnFailure() throws Exception {
        doThrow(new RuntimeException("repo not found"))
                .when(orchestrationService).startAnalysis(anyLong(), anyString());
        consumer.handleJava(msg(5L, "v1.0"), channel, 14L, null);
        verify(channel).basicNack(14L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void shouldGoListenerShareSemantics() throws Exception {
        consumer.handleGo(msg(7L, "v3.0"), channel, 15L, null);
        verify(orchestrationService).startAnalysis(7L, "v3.0");
        verify(channel).basicAck(15L, false);
    }
}
