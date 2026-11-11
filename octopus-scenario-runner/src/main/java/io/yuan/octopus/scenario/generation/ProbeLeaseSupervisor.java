package io.yuan.octopus.scenario.generation;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 探测运行的心跳与遗留回收，规则同 RunLeaseSupervisor：只回收心跳过期且不属于本进程的运行，不自动重放。
 * 遗留 RUNNING → INTERRUPTED（可能已调用被测接口，Observer 判为副作用未知）；遗留 QUEUED → INTERRUPTED（未调用，判为未探测）。
 */
@Component
public class ProbeLeaseSupervisor {
    private static final Logger log = LoggerFactory.getLogger(ProbeLeaseSupervisor.class);
    static final String QUEUED_REASON = "服务重启前探测尚未开始，未调用被测接口";
    static final String RUNNING_REASON = "探测执行被中断：可能已部分调用被测接口，请人工核查残留";

    private final ProbeRunRepository runs;
    private final ProbeExecutionService execution;
    private final long heartbeatSeconds, leaseSeconds;
    private ScheduledExecutorService scheduler;

    public ProbeLeaseSupervisor(ProbeRunRepository runs, ProbeExecutionService execution,
                                @Value("${scenario.testing.run-lease.heartbeat-seconds:15}") long heartbeatSeconds,
                                @Value("${scenario.testing.run-lease.lease-seconds:120}") long leaseSeconds) {
        this.runs = runs;
        this.execution = execution;
        this.heartbeatSeconds = Math.max(1, heartbeatSeconds);
        this.leaseSeconds = Math.max(this.heartbeatSeconds * 3, leaseSeconds);
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("probe-lease-supervisor").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::heartbeatSafely, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::recoverSafely, 10, Math.max(30, heartbeatSeconds * 2), TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() { if (scheduler != null) scheduler.shutdownNow(); }

    void heartbeatSafely() {
        try { var active = execution.activeRunIds(); if (!active.isEmpty()) runs.heartbeat(active, Instant.now()); }
        catch (Exception e) { log.warn("Probe heartbeat failed: {}", e.getMessage()); }
    }

    void recoverSafely() {
        try { int n = recover(Instant.now()); if (n > 0) log.warn("Recovered {} orphaned probe run(s)", n); }
        catch (Exception e) { log.error("Probe recovery failed: {}", e.getMessage()); }
    }

    int recover(Instant now) {
        var active = execution.activeRunIds();
        var expiry = now.minus(Duration.ofSeconds(leaseSeconds));
        int recovered = 0;
        for (var run : runs.findByStateIn(List.of("QUEUED", "RUNNING"))) {
            if (active.contains(run.id)) continue;
            Instant last = run.heartbeatAt != null ? run.heartbeatAt : run.createdAt;
            if (last == null || last.isAfter(expiry)) continue;
            boolean started = "RUNNING".equals(run.state);
            if (started) terminate(run.id);
            scrub(run.id);
            run.state = "INTERRUPTED";
            run.requestsMayHaveBeenSent = started;
            run.error = started ? RUNNING_REASON : QUEUED_REASON;
            run.finishedAt = now;
            try { runs.save(run); recovered++; }
            catch (ObjectOptimisticLockingFailureException concurrent) { log.debug("Probe run {} changed concurrently", run.id); }
        }
        return recovered;
    }

    private void terminate(String runId) {
        String marker = execution.directory(runId).resolve("plan.jmx").toString();
        ProcessHandle.allProcesses().filter(p -> p.info().commandLine().map(c -> c.contains(marker)).orElse(false)).forEach(p -> {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        });
    }

    private void scrub(String runId) {
        var dir = execution.directory(runId);
        for (String name : List.of("context.json", "connections.json", "environment.json", ProbeExecutionService.EVIDENCE_FILE)) {
            try { Files.deleteIfExists(dir.resolve(name)); } catch (Exception e) { log.warn("Could not delete {} of probe {}", name, runId); }
        }
    }
}
