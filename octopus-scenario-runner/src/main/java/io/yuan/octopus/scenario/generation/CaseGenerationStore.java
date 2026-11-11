package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;

import io.yuan.octopus.scenario.generation.CaseGenerationEngine.Task;
import io.yuan.octopus.scenario.testing.ContextCredentialCipher;
import io.yuan.octopus.scenario.testing.FlowFailure;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 生成任务快照存储：整体加密（探测初始上下文可能含敏感值），行级锁更新，请求标识幂等。 */
@Service
public class CaseGenerationStore {
    static final int MAX_SNAPSHOT_CHARS = 8_000_000;
    private final CaseGenerationTaskRepository repository;
    private final ContextCredentialCipher cipher;

    public CaseGenerationStore(CaseGenerationTaskRepository repository, ContextCredentialCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public Task create(Task task) {
        var existing = repository.findByRequestId(task.request.requestId());
        if (existing.isPresent()) return same(existing.get(), task);
        require(repository.findByActiveFlow(task.request.flowId()).isEmpty(), "该流程已有进行中的用例生成任务，请查看进度或先取消旧任务");
        var row = new CaseGenerationTaskEntity();
        row.id = task.id;
        row.requestId = task.request.requestId();
        row.requestHash = task.requestHash;
        row.flowId = task.request.flowId();
        row.flowRevision = task.request.flowRevision();
        row.probeMode = task.request.probe().mode().name();
        row.probeEnvironment = task.request.probe().environment();
        row.createdBy = task.createdBy;
        row.createdAt = Instant.parse(task.createdAt);
        write(row, task);
        try { return decode(repository.saveAndFlush(row)); }
        catch (DataIntegrityViolationException e) {
            return repository.findByRequestId(task.request.requestId()).map(r -> same(r, task))
                    .orElseThrow(() -> new IllegalArgumentException("该流程已有进行中的用例生成任务"));
        }
    }

    private Task same(CaseGenerationTaskEntity row, Task task) {
        require(row.requestHash.equals(task.requestHash), "同一请求标识不能对应不同的生成请求");
        return decode(row);
    }

    public Task get(String id) {
        return decode(repository.findById(id).orElseThrow(() -> new IllegalArgumentException("生成任务不存在")));
    }

    public List<Task> recent(String flowId) {
        var rows = flowId == null ? repository.findTop100ByOrderByUpdatedAtDesc() : repository.findTop50ByFlowIdOrderByCreatedAtDesc(flowId);
        List<Task> result = new ArrayList<>();
        for (var row : rows) try { result.add(decode(row)); } catch (FlowFailure ignored) { }
        return result;
    }

    public List<String> pending() { return repository.pending(); }

    @Transactional
    public Task update(String id, Consumer<Task> change) {
        var row = repository.locked(id).orElseThrow(() -> new IllegalArgumentException("生成任务不存在"));
        var task = decode(row);
        change.accept(task);
        write(row, task);
        repository.saveAndFlush(row);
        return task;
    }

    /** 用新快照整体替换（工作单元在锁外完成后写回）；next 收到当前持久化版本，返回要写入的版本。 */
    @Transactional
    public Task replace(String id, java.util.function.UnaryOperator<Task> next) {
        var row = repository.locked(id).orElseThrow(() -> new IllegalArgumentException("生成任务不存在"));
        var task = next.apply(decode(row));
        write(row, task);
        repository.saveAndFlush(row);
        return task;
    }

    private void write(CaseGenerationTaskEntity row, Task task) {
        if (!cipher.available()) throw new FlowFailure(FlowFailure.Code.STORAGE_UNAVAILABLE, null, "任务加密密钥未配置，请配置服务加密密钥后重试");
        row.state = task.state.name();
        row.phase = task.phase == null ? null : task.phase.name();
        row.roundNo = task.round;
        row.owner = task.owner;
        row.leaseUntil = task.leaseUntil;
        row.activeFlow = CaseGenerationEngine.terminal(task.state) ? null : row.flowId;
        String snapshot = canonical(task);
        if (snapshot.length() > MAX_SNAPSHOT_CHARS) throw new FlowFailure(FlowFailure.Code.STORAGE_UNAVAILABLE, null, "任务检查点过大，请减少候选数量后重新生成");
        row.updatedAt = Instant.now();
        row.snapshotEncrypted = cipher.encrypt(snapshot);
    }

    private Task decode(CaseGenerationTaskEntity row) {
        try {
            var task = GSON.fromJson(cipher.decrypt(row.snapshotEncrypted), Task.class);
            require(task.formatVersion == 1, "不支持的任务数据版本");
            return task;
        } catch (RuntimeException e) {
            throw new FlowFailure(FlowFailure.Code.STORAGE_UNAVAILABLE, null, "任务快照无法解密或读取，请检查服务加密密钥与数据版本");
        }
    }
}
