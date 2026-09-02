package io.yuan.octopus.scheduler.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑：主队列 + DLX + TTL 重试队列（重试 30s 后回投主队列）。
 * 消息统一 JSON 编码（Jackson 转换器），与 code-intelligence 的
 * ChangeReviewRabbitConfig 约定一致。
 */
@Configuration
public class RabbitConfig {

    public static final String JAVA_QUEUE = "octopus.analysis.java";
    public static final String RETRY_QUEUE = "octopus.analysis.retry";
    public static final String DLX = "octopus.analysis.dlx";
    public static final long RETRY_TTL_MILLIS = 30000L;

    @Bean
    public Queue javaQueue() {
        // x-max-length 防止 broker 积压无界。注意：队列参数变更需删除既有队列后重启
        // （参数不同的重复声明会 PRECONDITION_FAILED）
        return QueueBuilder.durable(JAVA_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", "retry")
                .withArgument("x-max-length", 10000)
                .withArgument("x-overflow", "drop-head")
                .build();
    }

    @Bean
    public Queue retryQueue() {
        // 必须设置 x-message-ttl：没有 TTL 消息死信进 retry 队列后永不过期、
        // 永不回投主队列，整个 DLX 重试机制失效
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", JAVA_QUEUE)
                .withArgument("x-message-ttl", RETRY_TTL_MILLIS)
                .build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX);
    }

    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(retryQueue()).to(deadLetterExchange()).with("retry");
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
