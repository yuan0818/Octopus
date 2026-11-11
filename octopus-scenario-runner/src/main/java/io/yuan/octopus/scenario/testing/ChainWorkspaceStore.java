package io.yuan.octopus.scenario.testing;
import java.util.List;
import java.util.function.Consumer;

/** Each update is one transaction; callers must not perform model calls inside it. */
interface ChainWorkspaceStore {
    FlowChainService.Run create(FlowChainService.Run run);
    FlowChainService.Run get(String id);
    FlowChainService.Run latest(String flowId);
    List<FlowChainService.TaskSummary> recent();
    List<FlowChainService.TaskSummary> tasks();
    List<String> pending();
    FlowChainService.Run update(String id, Consumer<FlowChainService.Run> change);
}
