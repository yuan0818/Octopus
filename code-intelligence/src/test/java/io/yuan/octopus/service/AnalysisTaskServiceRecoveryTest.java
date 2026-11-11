package io.yuan.octopus.service;

import io.yuan.octopus.entity.AnalysisTask;
import io.yuan.octopus.entity.AnalysisTaskStep;
import io.yuan.octopus.repository.AnalysisTaskRepository;
import io.yuan.octopus.repository.AnalysisTaskStepRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** M3：进程重启后 RUNNING 任务必须变为可重试的 FAILED，且中断步骤被标记，retry 才能续跑。 */
@ExtendWith(MockitoExtension.class)
class AnalysisTaskServiceRecoveryTest {

    @Mock private AnalysisTaskRepository taskRepository;
    @Mock private AnalysisTaskStepRepository stepRepository;
    @InjectMocks private AnalysisTaskService service;

    private static AnalysisTaskStep step(long id, String name, String status) {
        return AnalysisTaskStep.builder().id(id).taskId(1L).stepName(name).status(status).build();
    }

    @Test
    void marksRunningStepAndTaskFailed() {
        AnalysisTask task = AnalysisTask.builder().id(1L).status("RUNNING").build();
        AnalysisTaskStep sync = step(10, "SYNC", "COMPLETED");
        AnalysisTaskStep scan = step(11, "SCAN", "RUNNING");
        AnalysisTaskStep deep = step(12, "PARSE_DEEP", "PENDING");
        when(taskRepository.findByStatus("RUNNING")).thenReturn(List.of(task));
        when(stepRepository.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(sync, scan, deep));

        int count = service.failInterruptedTasks("restart");

        assertEquals(1, count);
        assertEquals("FAILED", task.getStatus());
        assertEquals("restart", task.getErrorMessage());
        assertEquals("COMPLETED", sync.getStatus());
        assertEquals("FAILED", scan.getStatus());
        assertEquals("PENDING", deep.getStatus());
        verify(stepRepository).save(scan);
        verify(taskRepository).save(task);
    }

    @Test
    void fallsBackToFirstPendingStepWhenNoneRunning() {
        // 任务在等待并发许可时被中断：所有步骤仍为 PENDING
        AnalysisTask task = AnalysisTask.builder().id(1L).status("RUNNING").build();
        AnalysisTaskStep sync = step(10, "SYNC", "PENDING");
        AnalysisTaskStep scan = step(11, "SCAN", "PENDING");
        when(taskRepository.findByStatus("RUNNING")).thenReturn(List.of(task));
        when(stepRepository.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(sync, scan));

        service.failInterruptedTasks("restart");

        assertEquals("FAILED", sync.getStatus());
        assertEquals("PENDING", scan.getStatus());
    }

    @Test
    void noRunningTasksIsNoop() {
        when(taskRepository.findByStatus("RUNNING")).thenReturn(List.of());
        assertEquals(0, service.failInterruptedTasks("restart"));
        verify(taskRepository, never()).save(any());
    }
}
