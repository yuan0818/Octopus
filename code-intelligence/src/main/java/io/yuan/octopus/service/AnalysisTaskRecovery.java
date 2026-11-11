package io.yuan.octopus.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时把上次进程中断的分析任务（仍为 RUNNING）标记为可重试的 FAILED。
 * <p>
 * 与 {@code ChangeReviewConsumer#recoverStuckTasks} 对应：change-review 任务会重新入队，
 * 普通分析任务没有持久化队列，因此只标记失败、由用户在分析任务页点击重试。
 * <p>
 * 前提：code-intelligence 单实例部署（分析管线本就跑在进程内线程池）。多实例部署时请设置
 * {@code octopus.analysis.recover-on-startup=false}，否则一个实例重启会把其他实例正在跑的任务标记失败。
 */
@Component
public class AnalysisTaskRecovery {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskRecovery.class);
    static final String REASON = "服务重启导致任务中断，可点击重试从中断步骤继续";

    private final AnalysisTaskService analysisTaskService;
    private final boolean enabled;

    public AnalysisTaskRecovery(AnalysisTaskService analysisTaskService,
                                @Value("${octopus.analysis.recover-on-startup:true}") boolean enabled) {
        this.analysisTaskService = analysisTaskService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        if (!enabled) {
            return;
        }
        try {
            int count = analysisTaskService.failInterruptedTasks(REASON);
            if (count > 0) {
                log.warn("Marked {} interrupted analysis task(s) as FAILED (retryable)", count);
            }
        } catch (Exception e) {
            // 恢复失败不应阻止服务启动
            log.error("Failed to recover interrupted analysis tasks: {}", e.getMessage(), e);
        }
    }
}
