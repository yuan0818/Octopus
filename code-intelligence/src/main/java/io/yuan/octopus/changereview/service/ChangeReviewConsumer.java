package io.yuan.octopus.changereview.service;

import com.rabbitmq.client.Channel;
import io.yuan.octopus.changereview.config.ChangeReviewRabbitConfig;
import io.yuan.octopus.changereview.dto.ChangeReviewMessage;
import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import io.yuan.octopus.changereview.repository.ChangeReviewTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consumes change-review messages and drives the pipeline (ADR-001). Manual ACK:
 * a task reaching any terminal state (COMPLETED/FAILED/PARTIALLY_COMPLETED) is ACKed
 * — it is not retried automatically; retry is operator-driven via /retry. Only
 * unexpected exceptions NACK (requeue=false) into the DLX/retry loop.
 *
 * <p>Idempotency / mutual exclusion: the ACK happens <b>after</b>
 * {@code orchestrator.run} completes, so a consumer crash mid-run means MQ
 * <b>will</b> redeliver. Redelivery (and the startup recovery re-publish) is
 * deduplicated by an optimistic claim: only the consumer that flips the task
 * PENDING → RUNNING actually runs it; everyone else ACKs and drops.
 *
 * <p>On application startup, tasks left RUNNING from a previous crash are reset
 * to PENDING and re-enqueued (BR-CTR-009).
 */
@Component
public class ChangeReviewConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChangeReviewConsumer.class);

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("COMPLETED", "FAILED", "PARTIALLY_COMPLETED", "SUPERSEDED");

    /** DLX 重试循环上限：超过后标记 FAILED 并 ack，避免持续性故障让消息在 retry 队列里永久打转。 */
    private static final int MAX_MQ_RETRIES = 5;

    private final ChangeReviewOrchestrator orchestrator;
    private final ChangeReviewTaskRepository taskRepository;
    private final ChangeReviewTaskService taskService;
    private final ChangeReviewProducer producer;

    public ChangeReviewConsumer(ChangeReviewOrchestrator orchestrator,
                                ChangeReviewTaskRepository taskRepository,
                                ChangeReviewTaskService taskService,
                                ChangeReviewProducer producer) {
        this.orchestrator = orchestrator;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.producer = producer;
    }

    @RabbitListener(queues = ChangeReviewRabbitConfig.CHANGE_REVIEW_QUEUE)
    public void handleChangeReview(ChangeReviewMessage message, Channel channel,
                                  @org.springframework.messaging.handler.annotation.Header(AmqpHeaders.DELIVERY_TAG) long tag,
                                  @org.springframework.messaging.handler.annotation.Header(name = "x-death", required = false) List<Map<String, ?>> xDeath) {
        Long taskId = message.taskId();
        try {
            ChangeReviewTask task = taskRepository.findById(taskId).orElse(null);
            if (task == null) {
                log.warn("Change-review task {} not found; acking and dropping", taskId);
                ack(channel, tag);
                return;
            }
            if (TERMINAL_STATUSES.contains(task.getStatus())) {
                // 终态任务不再重跑：MQ 重投 / 恢复补发到达已完成的任务时，
                // 直接丢弃，避免重复执行并再次向 Gerrit 发评论
                log.info("Task {} already in terminal state {}; acking without running",
                        taskId, task.getStatus());
                ack(channel, tag);
                return;
            }
            long deadLetterCount = deadLetterCount(xDeath);
            if (deadLetterCount > MAX_MQ_RETRIES) {
                // 重试耗尽：标记 FAILED 供人工 /retry，打破 30s TTL 的无限重试循环
                log.error("Task {} exceeded {} MQ retries; marking FAILED", taskId, MAX_MQ_RETRIES);
                task.setStatus("FAILED");
                task.setFailureType("RETRY_EXHAUSTED");
                task.setErrorMessage("Exceeded " + MAX_MQ_RETRIES + " MQ redeliveries");
                taskRepository.save(task);
                ack(channel, tag);
                return;
            }
            if (!taskService.claim(taskId)) {
                // 已被其他消费者占位（并发重投）或状态已变化：让持有者处理
                log.info("Task {} claimed by another consumer or not claimable; acking", taskId);
                ack(channel, tag);
                return;
            }
            orchestrator.run(taskId);
            ack(channel, tag);
        } catch (Exception e) {
            log.error("Unexpected error running change-review task {}; nacking for retry", taskId, e);
            // requeue=false → DLX → retry queue (TTL) → re-enqueue to main queue
            nack(channel, tag);
        }
    }

    /** 从 x-death header 取主队列的死信次数（每轮 DLX 循环 +1）。 */
    private long deadLetterCount(List<Map<String, ?>> xDeath) {
        if (xDeath == null) return 0;
        long count = 0;
        for (Map<String, ?> entry : xDeath) {
            if (ChangeReviewRabbitConfig.CHANGE_REVIEW_QUEUE.equals(entry.get("queue"))
                    && entry.get("count") instanceof Number n) {
                count = Math.max(count, n.longValue());
            }
        }
        return count;
    }

    /**
     * Crash recovery: tasks left RUNNING from a previous consumer crash are reset
     * to PENDING (so the claim-based mutual exclusion keeps working) and
     * re-enqueued.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStuckRunningTasks() {
        List<ChangeReviewTask> stuck = taskRepository.findByStatus("RUNNING");
        if (stuck.isEmpty()) return;
        log.info("Recovering {} stuck RUNNING change-review tasks on startup", stuck.size());
        for (ChangeReviewTask task : stuck) {
            try {
                if (!taskService.resetForRecovery(task.getId())) {
                    continue; // 已被并发复位/终态化
                }
                producer.publish(task.getId());
            } catch (Exception e) {
                log.warn("Failed to re-enqueue stuck task {}: {}", task.getId(), e.getMessage());
            }
        }
    }

    private void ack(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (java.io.IOException e) {
            log.warn("Failed to ack delivery {}: {}", tag, e.getMessage());
        }
    }

    private void nack(Channel channel, long tag) {
        try {
            channel.basicNack(tag, false, false);
        } catch (java.io.IOException e) {
            // channel 已关闭：交给容器按默认 requeue 语义处理
            log.warn("Failed to nack delivery {}: {}", tag, e.getMessage());
        }
    }
}
