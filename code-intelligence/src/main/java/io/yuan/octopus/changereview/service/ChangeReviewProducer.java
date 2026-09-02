package io.yuan.octopus.changereview.service;

import io.yuan.octopus.changereview.config.ChangeReviewRabbitConfig;
import io.yuan.octopus.changereview.dto.ChangeReviewMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a change-review task to the work queue and flips the task to QUEUED.
 * The webhook/manual controller returns 202 immediately after this (BR-CTR-007).
 */
@Component
public class ChangeReviewProducer {

    private static final Logger log = LoggerFactory.getLogger(ChangeReviewProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public ChangeReviewProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(Long taskId) {
        rabbitTemplate.convertAndSend(
                ChangeReviewRabbitConfig.CHANGE_REVIEW_QUEUE,
                ChangeReviewRabbitConfig.CHANGE_REVIEW_QUEUE,
                new ChangeReviewMessage(taskId));
        log.info("Published change-review task {} to queue {}", taskId, ChangeReviewRabbitConfig.CHANGE_REVIEW_QUEUE);
    }
}
