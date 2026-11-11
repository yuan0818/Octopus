package io.yuan.octopus.scheduler.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 分析任务队列声明（消费侧镜像）：与 octopus-analysis-scheduler 的 RabbitConfig
 * 参数完全一致——重复声明幂等，同时保证 scheduler 未运行时消费者也能独立 attach。
 * 拓扑：主队列（JAVA/GO）+ DLX + TTL 重试队列（30s 后回投各自主队列）。
 */
@Configuration
public class AnalysisRabbitConfig {

    public static final String JAVA_QUEUE = "octopus.analysis.java";
    public static final String GO_QUEUE = "octopus.analysis.go";
    public static final String RETRY_QUEUE = "octopus.analysis.retry";
    public static final String GO_RETRY_QUEUE = "octopus.analysis.go.retry";
    public static final String DLX = "octopus.analysis.dlx";
    public static final long RETRY_TTL_MILLIS = 30000L;

    @Bean
    public Queue analysisJavaQueue() {
        return QueueBuilder.durable(JAVA_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", "retry")
                .withArgument("x-max-length", 10000)
                .withArgument("x-overflow", "drop-head")
                .build();
    }

    @Bean
    public Queue analysisGoQueue() {
        return QueueBuilder.durable(GO_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", "retry.go")
                .withArgument("x-max-length", 10000)
                .withArgument("x-overflow", "drop-head")
                .build();
    }

    @Bean
    public Queue analysisRetryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", JAVA_QUEUE)
                .withArgument("x-message-ttl", RETRY_TTL_MILLIS)
                .build();
    }

    @Bean
    public Queue analysisGoRetryQueue() {
        return QueueBuilder.durable(GO_RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", GO_QUEUE)
                .withArgument("x-message-ttl", RETRY_TTL_MILLIS)
                .build();
    }

    @Bean
    public DirectExchange analysisDeadLetterExchange() {
        return new DirectExchange(DLX);
    }

    @Bean
    public Binding analysisRetryBinding() {
        return BindingBuilder.bind(analysisRetryQueue()).to(analysisDeadLetterExchange()).with("retry");
    }

    @Bean
    public Binding analysisGoRetryBinding() {
        return BindingBuilder.bind(analysisGoRetryQueue()).to(analysisDeadLetterExchange()).with("retry.go");
    }
}
