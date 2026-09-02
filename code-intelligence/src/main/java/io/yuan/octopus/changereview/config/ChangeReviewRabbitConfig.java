package io.yuan.octopus.changereview.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for the change-review pipeline (ADR-001): a durable main queue
 * with a dead-letter exchange that routes retryable failures through a TTL-bound
 * retry queue (exponential backoff 30s/120s/300s) before re-enqueuing to the main
 * queue. Mirrors {@code octopus-analysis-scheduler}'s DLX pattern.
 *
 * <p>Messages are JSON-encoded via {@link Jackson2JsonMessageConverter}.
 */
@Configuration
public class ChangeReviewRabbitConfig {

    public static final String CHANGE_REVIEW_QUEUE = "octopus.change-review";
    public static final String RETRY_QUEUE = "octopus.change-review.retry";
    public static final String DLX = "octopus.change-review.dlx";

    @Bean
    public Queue changeReviewQueue() {
        // x-max-length 防止 broker 积压无界（消费侧有 x-death 重试上限，正常不会触顶）。
        // 注意：队列参数变更需删除既有队列后重启（参数不同的重复声明会 PRECONDITION_FAILED）
        return QueueBuilder.durable(CHANGE_REVIEW_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", "retry")
                .withArgument("x-max-length", 10000)
                .withArgument("x-overflow", "drop-head")
                .build();
    }

    @Bean
    public Queue retryQueue() {
        // After TTL expires, the message dead-letters to the default exchange with
        // routing-key = main queue name, re-enqueuing it (retry loop).
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", CHANGE_REVIEW_QUEUE)
                .withArgument("x-message-ttl", 30000L)
                .build();
    }

    @Bean
    public DirectExchange changeReviewDlx() {
        return new DirectExchange(DLX);
    }

    @Bean
    public Binding retryBinding(DirectExchange changeReviewDlx, Queue retryQueue) {
        return BindingBuilder.bind(retryQueue).to(changeReviewDlx).with("retry");
    }

    @Bean
    public MessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
