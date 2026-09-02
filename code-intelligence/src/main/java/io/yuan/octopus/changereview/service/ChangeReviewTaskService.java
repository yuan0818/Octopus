package io.yuan.octopus.changereview.service;

import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import io.yuan.octopus.changereview.repository.ChangeReviewTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 变更审查任务的状态写入服务。
 * <p>
 * {@code @Modifying} 查询（supersede / claim / reset）必须有事务边界——
 * controller 直接调用仓储会抛 {@code TransactionRequiredException}，
 * 统一收敛到这里包事务。
 */
@Service
public class ChangeReviewTaskService {

    private final ChangeReviewTaskRepository taskRepository;

    public ChangeReviewTaskService(ChangeReviewTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * 创建任务并取代同 Change 的在途任务（supersede + save 原子执行）。
     * 调用方在事务提交后再 publish；publish 失败由调用方标记任务失败。
     */
    @Transactional
    public ChangeReviewTask createAndSupersede(ChangeReviewTask task) {
        taskRepository.markInFlightAsSuperseded(task.getRepoConfigId(), task.getChangeNumber(), null);
        return taskRepository.save(task);
    }

    /**
     * 消费侧乐观占位（PENDING → RUNNING）。返回 false 表示任务已被
     * 其他消费者持有或已终态，调用方应直接 ACK 跳过。
     */
    @Transactional
    public boolean claim(Long taskId) {
        return taskRepository.claimPending(taskId) == 1;
    }

    /**
     * 崩溃恢复：把滞留 RUNNING 的任务复位为 PENDING（重新入队前调用）。
     */
    @Transactional
    public boolean resetForRecovery(Long taskId) {
        return taskRepository.resetRunningToPending(taskId) == 1;
    }
}
