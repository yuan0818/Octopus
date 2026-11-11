package io.yuan.octopus.scheduler.consumer;

import com.rabbitmq.client.Channel;
import io.yuan.octopus.infra.common.mq.AnalysisTaskMessage;
import io.yuan.octopus.scheduler.config.AnalysisRabbitConfig;
import io.yuan.octopus.service.AnalysisOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 消费 octopus-analysis-scheduler 发布的分析任务，驱动 tag 管线
 * （SYNC→SCAN→PARSE_DEEP，语言路由由 AnalysisDispatcher 在管线内完成）。
 * <p>
 * 重试语义与 ChangeReviewConsumer 一致：异常 nack（requeue=false）→ DLX →
 * 30s TTL 重试队列 → 回投主队列；x-death 超过 {@link #MAX_MQ_RETRIES} 后
 * 放弃（ack 丢弃），避免毒消息无限循环。
 * <p>
 * 已知限制：scheduler 侧重发的任务是追踪记录，本消费者不回写其状态
 * （两模块任务实体相互独立）；执行进度看 code-intelligence 自己的
 * analysis_task。
 */
@Component
public class AnalysisTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskConsumer.class);
    private static final int MAX_MQ_RETRIES = 5;

    private final AnalysisOrchestrationService orchestrationService;

    public AnalysisTaskConsumer(AnalysisOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @RabbitListener(queues = AnalysisRabbitConfig.JAVA_QUEUE)
    public void handleJava(AnalysisTaskMessage message, Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long tag,
                           @Header(name = "x-death", required = false) List<Map<String, ?>> xDeath) {
        handleMessage(message, channel, tag, xDeath);
    }

    @RabbitListener(queues = AnalysisRabbitConfig.GO_QUEUE)
    public void handleGo(AnalysisTaskMessage message, Channel channel,
                         @Header(AmqpHeaders.DELIVERY_TAG) long tag,
                         @Header(name = "x-death", required = false) List<Map<String, ?>> xDeath) {
        handleMessage(message, channel, tag, xDeath);
    }

    private void handleMessage(AnalysisTaskMessage message, Channel channel, long tag,
                               List<Map<String, ?>> xDeath) {
        try {
            if (message == null || message.repoConfigId() == null || isBlank(message.tag())) {
                // 结构性缺陷的消息重试也不可能成功：ack 丢弃并留痕
                log.error("Invalid analysis task message (missing repoConfigId/tag), dropping: {}", message);
                ack(channel, tag);
                return;
            }
            if (deadLetterCount(xDeath) > MAX_MQ_RETRIES) {
                log.error("Analysis task for repo {} tag {} exceeded {} MQ retries; dropping",
                        message.repoConfigId(), message.tag(), MAX_MQ_RETRIES);
                ack(channel, tag);
                return;
            }
            log.info("Consuming analysis task: repo={} tag={} language={}",
                    message.repoConfigId(), message.tag(), message.language());
            orchestrationService.startAnalysis(message.repoConfigId(), message.tag());
            ack(channel, tag);
        } catch (Exception e) {
            log.error("Analysis task failed (repo={} tag={}); nacking for retry",
                    message != null ? message.repoConfigId() : null, message != null ? message.tag() : null, e);
            nack(channel, tag);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 从 x-death header 取死信次数（每轮 DLX 循环 +1）。 */
    private static long deadLetterCount(List<Map<String, ?>> xDeath) {
        if (xDeath == null) return 0;
        long count = 0;
        for (Map<String, ?> death : xDeath) {
            Object c = death.get("count");
            if (c instanceof Number n) {
                count = Math.max(count, n.longValue());
            }
        }
        return count;
    }

    private static void ack(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (Exception ignored) {
        }
    }

    private static void nack(Channel channel, long tag) {
        try {
            // requeue=false → DLX → retry 队列（TTL）→ 回投主队列
            channel.basicNack(tag, false, false);
        } catch (Exception ignored) {
        }
    }
}
