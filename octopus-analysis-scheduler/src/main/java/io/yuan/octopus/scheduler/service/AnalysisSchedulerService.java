package io.yuan.octopus.scheduler.service;

import io.yuan.octopus.infra.common.mq.AnalysisTaskMessage;
import io.yuan.octopus.scheduler.config.RabbitConfig;
import io.yuan.octopus.scheduler.entity.AnalysisTask;
import io.yuan.octopus.scheduler.repository.AnalysisTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AnalysisSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisSchedulerService.class);

    private final AnalysisTaskRepository taskRepository;
    private final RabbitTemplate rabbitTemplate;

    public AnalysisSchedulerService(AnalysisTaskRepository taskRepository, RabbitTemplate rabbitTemplate) {
        this.taskRepository = taskRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Create an analysis task, detect language, route to the correct queue.
     * <p>
     * 消息体使用 record + Jackson 序列化（可空字段安全）——此前用 {@code Map.of}
     * 装配，tag/projectId 等选填字段不传直接 NPE。
     */
    public AnalysisTask createTask(CreateTaskRequest request) {
        // 1. Create task
        AnalysisTask task = AnalysisTask.builder()
                .repoConfigId(request.repoConfigId())
                .projectId(request.projectId())
                .branch(request.branch())
                .commitId(request.commitId())
                .tag(request.tag())
                .language(request.language() != null ? request.language().toUpperCase() : "JAVA")
                .analysisType(request.analysisType() != null ? request.analysisType() : "FULL")
                .status("PENDING")
                .maxRetries(3)
                .timeoutSeconds(1800)
                .build();
        task = taskRepository.save(task);

        // 2. Route to queue by language；未知语言显式拒绝——静默错发到 JAVA
        // 队列会让任务在错误的管线里失败，且无迹可查
        String queue = switch (task.getLanguage()) {
            case "JAVA" -> RabbitConfig.JAVA_QUEUE;
            case "GO" -> RabbitConfig.GO_QUEUE;
            default -> throw new IllegalArgumentException(
                    "Unsupported analysis language: " + task.getLanguage());
        };
        task.setQueueName(queue);

        // 3. Send to RabbitMQ（publish 失败时任务标记 FAILED，避免永久滞留 PENDING 无迹可查）
        try {
            rabbitTemplate.convertAndSend(queue, queue, toMessage(task));
            log.info("Task {} sent to queue {} (language={})", task.getId(), queue, task.getLanguage());
        } catch (Exception e) {
            log.error("Failed to publish task {} to queue {}: {}", task.getId(), queue, e.getMessage());
            task.setStatus("FAILED");
            task.setErrorMessage("MQ publish failed: " + e.getMessage());
            return taskRepository.save(task);
        }

        task.setStatus("QUEUED");
        task = taskRepository.save(task);
        return task;
    }

    public AnalysisTask getTask(Long id) {
        return taskRepository.findById(id).orElseThrow();
    }

    public AnalysisTask cancelTask(Long id) {
        AnalysisTask task = getTask(id);
        if ("PENDING".equals(task.getStatus()) || "QUEUED".equals(task.getStatus())) {
            task.setStatus("CANCELLED");
            task = taskRepository.save(task);
        }
        return task;
    }

    /**
     * 重试失败任务：重派<b>原任务</b>（原 taskId 重新入队），
     * 不再新建一条任务——旧实现把原任务置回 PENDING 却永不投递，形成悬挂记录。
     */
    public AnalysisTask retryTask(Long id) {
        AnalysisTask task = getTask(id);
        if (!"FAILED".equals(task.getStatus())) {
            return task;
        }
        task.setStatus("QUEUED");
        task.setRetryCount(task.getRetryCount() + 1);
        task.setErrorMessage(null);
        task = taskRepository.save(task);

        String queue = task.getQueueName() != null ? task.getQueueName() : RabbitConfig.JAVA_QUEUE;
        try {
            rabbitTemplate.convertAndSend(queue, queue, toMessage(task));
            log.info("Task {} re-dispatched to queue {}", task.getId(), queue);
        } catch (Exception e) {
            log.error("Failed to re-publish task {}: {}", task.getId(), e.getMessage());
            task.setStatus("FAILED");
            task.setErrorMessage("MQ publish failed: " + e.getMessage());
            task = taskRepository.save(task);
        }
        return task;
    }

    private AnalysisTaskMessage toMessage(AnalysisTask task) {
        return new AnalysisTaskMessage(task.getId(), task.getRepoConfigId(), task.getProjectId(),
                task.getBranch(), task.getCommitId(), task.getTag(), task.getLanguage(),
                task.getAnalysisType(), java.time.LocalDateTime.now().toString());
    }

    public record CreateTaskRequest(
            Long repoConfigId, Long projectId, String branch, String commitId,
            String tag, String language, String analysisType) {}
}
