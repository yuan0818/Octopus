package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import java.util.*;
import java.util.function.Consumer;

/** Transactional copy-on-write fixture; production always uses the encrypted JPA store. */
class MemoryChainWorkspaceStore implements ChainWorkspaceStore {
    final Map<String,String> data=new LinkedHashMap<>();
    public synchronized FlowChainService.Run create(FlowChainService.Run run) {
        for(var id:data.keySet()) {
            var old=get(id);
            if(old.request.requestId().equals(run.request.requestId())){require(old.requestHash.equals(run.requestHash),"请求内容不一致");return old;}
            require(!old.request.flow().flowId().equals(run.request.flow().flowId()) || FlowChainService.terminal(old.state),"流程已有任务");
        }
        data.put(run.id,canonical(run));return get(run.id);
    }
    public synchronized FlowChainService.Run get(String id) { require(data.containsKey(id),"任务不存在");return read(data.get(id),FlowChainService.Run.class); }
    public synchronized FlowChainService.Run latest(String id) { return data.keySet().stream().map(this::get).filter(r -> r.request.flow().flowId().equals(id)).reduce((a,b)->b).orElse(null); }
    public synchronized List<FlowChainService.TaskSummary> recent() { return data.keySet().stream().map(this::get).map(FlowChainService::summary).toList(); }
    public synchronized List<FlowChainService.TaskSummary> tasks() { return recent(); }
    public synchronized List<String> pending() { return data.keySet().stream().filter(id -> Set.of("QUEUED","RUNNING","PAUSE_REQUESTED","ACCEPTING").contains(get(id).state.name())).toList(); }
    public synchronized FlowChainService.Run update(String id,Consumer<FlowChainService.Run> change) { var run=get(id);change.accept(run);data.put(id,canonical(run));return get(id); }
}
