package io.yuan.octopus.changereview.service;

import com.rabbitmq.client.Channel;
import io.yuan.octopus.changereview.dto.ChangeReviewMessage;
import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import io.yuan.octopus.changereview.repository.ChangeReviewTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChangeReviewConsumerTest {

    @Mock private ChangeReviewOrchestrator orchestrator;
    @Mock private ChangeReviewTaskRepository taskRepository;
    @Mock private ChangeReviewTaskService taskService;
    @Mock private ChangeReviewProducer producer;
    @Mock private Channel channel;

    private ChangeReviewConsumer consumer;

    private static final Long TASK_ID = 1L;

    @BeforeEach
    void setUp() {
        consumer = new ChangeReviewConsumer(orchestrator, taskRepository, taskService, producer);
    }

    private ChangeReviewTask task(String status) {
        ChangeReviewTask task = ChangeReviewTask.builder()
                .id(TASK_ID).repoConfigId(100L).platform("GERRIT")
                .changeNumber("123").patchsetNumber(1).targetBranch("main")
                .status(status).triggerSource("WEBHOOK")
                .build();
        return task;
    }

    // ── 终态守卫：不重跑、不发重复评论 ───────────────────────────

    @Test
    void shouldAckWithoutRunningWhenTaskCompleted() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task("COMPLETED")));

        consumer.handleChangeReview(new ChangeReviewMessage(TASK_ID), channel, 1L, null);

        verify(channel).basicAck(1L, false);
        verify(orchestrator, never()).run(anyLong());
        verify(taskService, never()).claim(anyLong());
    }

    @Test
    void shouldAckWithoutRunningWhenTaskSuperseded() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task("SUPERSEDED")));

        consumer.handleChangeReview(new ChangeReviewMessage(TASK_ID), channel, 1L, null);

        verify(channel).basicAck(1L, false);
        verify(orchestrator, never()).run(anyLong());
    }

    // ── 任务不存在：丢弃 ────────────────────────────────────────

    @Test
    void shouldAckWhenTaskMissing() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

        consumer.handleChangeReview(new ChangeReviewMessage(TASK_ID), channel, 1L, null);

        verify(channel).basicAck(1L, false);
        verify(orchestrator, never()).run(anyLong());
    }

    // ── 占位互斥：claim 失败让持有者处理 ────────────────────────

    @Test
    void shouldAckWhenClaimLost() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task("PENDING")));
        when(taskService.claim(TASK_ID)).thenReturn(false);

        consumer.handleChangeReview(new ChangeReviewMessage(TASK_ID), channel, 1L, null);

        verify(channel).basicAck(1L, false);
        verify(orchestrator, never()).run(anyLong());
    }

    // ── 正常路径：claim 成功 → 执行 → ack ───────────────────────

    @Test
    void shouldRunAndAckWhenClaimed() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task("PENDING")));
        when(taskService.claim(TASK_ID)).thenReturn(true);

        consumer.handleChangeReview(new ChangeReviewMessage(TASK_ID), channel, 1L, null);

        verify(orchestrator).run(TASK_ID);
        verify(channel).basicAck(1L, false);
    }

    // ── x-death 重试上限：标记 FAILED 并 ack，打破重试循环 ───────

    @Test
    void shouldMarkFailedAndAckWhenRetriesExhausted() throws Exception {
        ChangeReviewTask t = task("PENDING");
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(t));
        List<Map<String, ?>> xDeath = List.of(
                Map.of("queue", "octopus.change-review", "count", 6L, "reason", "rejected"));

        consumer.handleChangeReview(new ChangeReviewMessage(TASK_ID), channel, 1L, xDeath);

        ArgumentCaptor<ChangeReviewTask> captor = ArgumentCaptor.forClass(ChangeReviewTask.class);
        verify(taskRepository).save(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals("RETRY_EXHAUSTED", captor.getValue().getFailureType());
        verify(channel).basicAck(1L, false);
        verify(orchestrator, never()).run(anyLong());
    }

    @Test
    void shouldStillRunWhenRetriesNotExhausted() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task("PENDING")));
        when(taskService.claim(TASK_ID)).thenReturn(true);
        List<Map<String, ?>> xDeath = List.of(
                Map.of("queue", "octopus.change-review", "count", 3L));

        consumer.handleChangeReview(new ChangeReviewMessage(TASK_ID), channel, 1L, xDeath);

        verify(orchestrator).run(TASK_ID);
        verify(channel).basicAck(1L, false);
    }

    // ── 未捕获异常 → nack 进重试循环 ────────────────────────────

    @Test
    void shouldNackWhenOrchestratorThrows() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task("PENDING")));
        when(taskService.claim(TASK_ID)).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(orchestrator).run(TASK_ID);

        consumer.handleChangeReview(new ChangeReviewMessage(TASK_ID), channel, 1L, null);

        verify(channel).basicNack(1L, false, false);
    }

    // ── 崩溃恢复：RUNNING → 复位 PENDING → 重新入队 ─────────────

    @Test
    void shouldResetAndRepublishStuckRunningTasksOnStartup() {
        ChangeReviewTask stuck = task("RUNNING");
        when(taskRepository.findByStatus("RUNNING")).thenReturn(List.of(stuck));
        when(taskService.resetForRecovery(TASK_ID)).thenReturn(true);

        consumer.recoverStuckRunningTasks();

        verify(producer).publish(TASK_ID);
    }

    @Test
    void shouldSkipRecoveryWhenResetLostRace() {
        ChangeReviewTask stuck = task("RUNNING");
        when(taskRepository.findByStatus("RUNNING")).thenReturn(List.of(stuck));
        when(taskService.resetForRecovery(TASK_ID)).thenReturn(false);

        consumer.recoverStuckRunningTasks();

        verify(producer, never()).publish(anyLong());
    }

    @Test
    void shouldNotThrowWhenRecoveryPublishFails() {
        ChangeReviewTask stuck = task("RUNNING");
        when(taskRepository.findByStatus("RUNNING")).thenReturn(List.of(stuck));
        when(taskService.resetForRecovery(TASK_ID)).thenReturn(true);
        doThrow(new RuntimeException("mq down")).when(producer).publish(anyLong());

        assertDoesNotThrow(() -> consumer.recoverStuckRunningTasks());
    }
}
