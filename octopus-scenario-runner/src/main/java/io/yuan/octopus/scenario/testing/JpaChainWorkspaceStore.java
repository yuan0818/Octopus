package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

@Service
public class JpaChainWorkspaceStore implements ChainWorkspaceStore {
    private final FlowChainWorkspaceRepository repository;
    private final ContextCredentialCipher cipher;
    public JpaChainWorkspaceStore(FlowChainWorkspaceRepository repository,ContextCredentialCipher cipher) { this.repository=repository;this.cipher=cipher; }
    public FlowChainService.Run create(FlowChainService.Run run) {
        var existing=repository.findByRequestId(run.request.requestId());
        if(existing.isPresent()) return sameRequest(existing.get(),run);
        require(repository.findByActiveFlow(run.request.flow().flowId()).isEmpty(),"该流程已有补全任务，请查看进度、继续任务或先取消旧任务");
        var row=new FlowChainWorkspace();row.id=run.id;row.requestId=run.request.requestId();row.requestHash=run.requestHash;
        row.flowId=run.request.flow().flowId();row.createdAt=Instant.parse(run.createdAt);write(row,run);
        try { return decode(repository.saveAndFlush(row)); }
        catch(DataIntegrityViolationException e) {
            // saveAndFlush owns its transaction. The unique keys arbitrate simultaneous POSTs across instances.
            return repository.findByRequestId(run.request.requestId()).map(r -> sameRequest(r,run))
                    .orElseThrow(() -> new IllegalArgumentException("该流程已有补全任务，请重新连接现有任务"));
        }
    }
    private FlowChainService.Run sameRequest(FlowChainWorkspace row,FlowChainService.Run run) {
        require(row.requestHash.equals(run.requestHash),"同一请求标识不能对应不同的补全内容");return decode(row);
    }
    public FlowChainService.Run get(String id) { return decode(repository.findById(id).orElseThrow(() -> new IllegalArgumentException("补全任务不存在"))); }
    public FlowChainService.Run latest(String flowId) { return repository.findFirstByFlowIdOrderByCreatedAtDesc(flowId).map(this::decode).orElse(null); }
    public List<FlowChainService.TaskSummary> recent() {
        return repository.findTop50ByStateNotOrderByUpdatedAtDesc("CANCELLED").stream().map(this::summary).toList();
    }
    public List<FlowChainService.TaskSummary> tasks() {
        return repository.findTop100ByOrderByUpdatedAtDescIdDesc().stream().map(this::summary).toList();
    }
    private FlowChainService.TaskSummary summary(FlowChainWorkspace row) {
        try { return FlowChainService.summary(decode(row)); }
        catch(FlowFailure e) { return new FlowChainService.TaskSummary(row.id,row.flowId,"任务快照暂不可读 · " + row.flowId,FlowChainService.State.FAILED,"",row.updatedAt.toString(),0,0,e.detail("RECOVERY",null),row.createdAt.toString(),"",null,null,null); }
    }
    public List<String> pending() { return repository.pending(); }
    @Transactional
    public FlowChainService.Run update(String id,Consumer<FlowChainService.Run> change) {
        var row=repository.locked(id).orElseThrow(() -> new IllegalArgumentException("补全任务不存在"));
        var run=decode(row);change.accept(run);write(row,run);repository.saveAndFlush(row);return run;
    }
    private void write(FlowChainWorkspace row,FlowChainService.Run run) {
        if(!cipher.available()) throw new FlowFailure(FlowFailure.Code.STORAGE_UNAVAILABLE,null,"任务加密密钥未配置，请配置服务加密密钥后重试");
        row.state=run.state.name();row.activeFlow=FlowChainService.terminal(run.state)?null:row.flowId;
        String snapshot=canonical(run);
        if(snapshot.length()>8_000_000)throw new FlowFailure(FlowFailure.Code.STORAGE_UNAVAILABLE,null,"任务检查点过大，请缩短链路或减少候选数据后重新补全");
        row.updatedAt=Instant.parse(run.updatedAt);row.snapshotEncrypted=cipher.encrypt(snapshot);
    }
    private FlowChainService.Run decode(FlowChainWorkspace row) {
        try { var run=read(cipher.decrypt(row.snapshotEncrypted),FlowChainService.Run.class);require(run.formatVersion==1,"不支持的任务数据版本");return run; }
        catch(RuntimeException e) { throw new FlowFailure(FlowFailure.Code.STORAGE_UNAVAILABLE,null,"任务快照无法解密或读取，请检查服务加密密钥与数据版本"); }
    }
}
