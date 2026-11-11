package io.yuan.octopus.scenario.testing;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Phase 2.2：运行的租约（心跳）与遗留运行恢复。
 * <p>
 * 执行队列在进程内（2 个 JMeter 进程 + 32 个等待位）。进程退出后，数据库里遗留的 QUEUED / RUNNING
 * 以前会一直停在原状态。现在：
 * <ul>
 *   <li>本进程每 {@code heartbeat-seconds} 秒为自己仍在排队/执行的运行更新心跳；</li>
 *   <li>心跳超过 {@code lease-seconds} 未更新、且不属于本进程活动集合的运行被认定为遗留：
 *     <ul>
 *       <li>QUEUED → INTERRUPTED，原因注明"尚未开始执行，未调用被测接口，可直接重新运行"；</li>
 *       <li>RUNNING → INTERRUPTED，原因注明"可能已部分调用被测接口，请检查测试数据后再重新运行"。
 *           <b>不自动重放</b>——已执行的写操作可能已产生副作用（沿用 BUSINESS_TESTING.md 的约束）。</li>
 *     </ul>
 *   </li>
 *   <li>恢复时结束该运行目录下残留的 JMeter 进程，并删除含初始上下文/连接凭据的临时文件
 *       （正常结束时由 finally 删除，进程被强杀时不会执行）。</li>
 * </ul>
 * 多实例部署安全：只回收心跳过期的运行，不会误伤其他存活实例的运行。
 */
@Component
public class RunLeaseSupervisor {

    private static final Logger log = LoggerFactory.getLogger(RunLeaseSupervisor.class);

    /** 当前进程实例标识。 */
    public static final String WORKER_ID = hostName() + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID().toString().substring(0, 8);

    static final String QUEUED_REASON = "服务重启前尚未开始执行（未调用被测接口），可直接重新运行";
    static final String RUNNING_REASON = "执行被中断（服务或 JMeter 进程退出）：可能已部分调用被测接口，请检查测试数据后再重新运行";

    private final BusinessTestRunRepository runs;
    private final BusinessTestExecutionService execution;
    private final long heartbeatSeconds;
    private final long leaseSeconds;
    private ScheduledExecutorService scheduler;

    public RunLeaseSupervisor(BusinessTestRunRepository runs,
                              BusinessTestExecutionService execution,
                              @Value("${scenario.testing.run-lease.heartbeat-seconds:15}") long heartbeatSeconds,
                              @Value("${scenario.testing.run-lease.lease-seconds:120}") long leaseSeconds) {
        this.runs = runs;
        this.execution = execution;
        this.heartbeatSeconds = Math.max(1, heartbeatSeconds);
        this.leaseSeconds = Math.max(this.heartbeatSeconds * 3, leaseSeconds);
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("run-lease-supervisor").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::heartbeatSafely, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        // 启动后先等一个租约周期再回收，给其他实例的心跳留出时间
        scheduler.scheduleWithFixedDelay(this::recoverSafely, 10, Math.max(30, heartbeatSeconds * 2), TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    void heartbeatSafely() {
        try {
            Set<String> active = execution.activeRunIds();
            if (!active.isEmpty()) runs.heartbeat(active, Instant.now());
        } catch (Exception e) {
            log.warn("Run heartbeat failed: {}", e.getMessage());
        }
    }

    void recoverSafely() {
        try {
            int recovered = recover(Instant.now());
            if (recovered > 0) log.warn("Recovered {} orphaned business test run(s)", recovered);
        } catch (Exception e) {
            log.error("Run recovery failed: {}", e.getMessage(), e);
        }
    }

    /** 回收心跳过期的遗留运行，返回回收数量。 */
    int recover(Instant now) {
        Set<String> active = execution.activeRunIds();
        Instant expiry = now.minus(Duration.ofSeconds(leaseSeconds));
        int recovered = 0;
        List<BusinessTestRun> candidates = runs.findByStateIn(List.of("QUEUED", "RUNNING"));
        for (BusinessTestRun run : candidates) {
            if (active.contains(run.id)) continue;
            Instant last = run.heartbeatAt != null ? run.heartbeatAt : run.createdAt;
            if (last == null || last.isAfter(expiry)) continue;
            boolean started = "RUNNING".equals(run.state);
            if (started) terminateOrphanProcesses(run.id);
            scrubSecrets(run.id);
            run.state = "INTERRUPTED";
            run.verdict = "INCONCLUSIVE";
            run.error = started ? RUNNING_REASON : QUEUED_REASON;
            run.finishedAt = now;
            try {
                runs.save(run);
                recovered++;
            } catch (ObjectOptimisticLockingFailureException concurrent) {
                // 运行刚好被其所有者更新（心跳或结束），说明它仍然存活
                log.debug("Run {} changed concurrently; not recovered", run.id);
            }
        }
        return recovered;
    }

    /** 结束命令行中包含该运行目录的 JMeter 进程（只匹配本服务创建的运行目录）。 */
    private void terminateOrphanProcesses(String runId) {
        String marker = execution.artifactDirectory(runId).resolve("plan.jmx").toString();
        ProcessHandle.allProcesses()
                .filter(p -> p.info().commandLine().map(cmd -> cmd.contains(marker)).orElse(false))
                .forEach(p -> {
                    log.warn("Terminating orphaned JMeter process {} for run {}", p.pid(), runId);
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                });
    }

    /** 删除可能残留的初始上下文 / 环境 / 连接凭据临时文件。 */
    private void scrubSecrets(String runId) {
        Path directory = execution.artifactDirectory(runId);
        for (String name : List.of("context.json", "connections.json", "environment.json")) {
            try {
                Files.deleteIfExists(directory.resolve(name));
            } catch (Exception e) {
                log.warn("Could not delete {} of run {}: {}", name, runId, e.getClass().getSimpleName());
            }
        }
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}
