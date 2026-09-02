package io.yuan.octopus.scheduler.controller;

import io.yuan.octopus.scheduler.entity.AnalysisTask;
import io.yuan.octopus.scheduler.repository.AnalysisTaskRepository;
import io.yuan.octopus.scheduler.service.AnalysisSchedulerService;
import io.yuan.octopus.infra.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final AnalysisSchedulerService schedulerService;
    private final AnalysisTaskRepository taskRepository;

    public TaskController(AnalysisSchedulerService schedulerService, AnalysisTaskRepository taskRepository) {
        this.schedulerService = schedulerService;
        this.taskRepository = taskRepository;
    }

    @PostMapping
    public Result<AnalysisTask> create(@RequestBody AnalysisSchedulerService.CreateTaskRequest request) {
        AnalysisTask task = schedulerService.createTask(request);
        return Result.success(task);
    }

    /** List tasks by status / repoConfigId（分页，缺省 0/50，size 上限 200）。 */
    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long repoConfigId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest
                .of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        org.springframework.data.domain.Page<AnalysisTask> result;
        if (status != null) {
            result = taskRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else if (repoConfigId != null) {
            result = taskRepository.findByRepoConfigIdOrderByCreatedAtDesc(repoConfigId, pageable);
        } else {
            result = taskRepository.findAll(pageable);
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("content", result.getContent());
        data.put("totalElements", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        return Result.success(data);
    }

    @GetMapping("/{id}")
    public Result<AnalysisTask> get(@PathVariable Long id) {
        return Result.success(schedulerService.getTask(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<AnalysisTask> cancel(@PathVariable Long id) {
        return Result.success(schedulerService.cancelTask(id));
    }

    @PostMapping("/{id}/retry")
    public Result<AnalysisTask> retry(@PathVariable Long id) {
        return Result.success(schedulerService.retryTask(id));
    }
}
