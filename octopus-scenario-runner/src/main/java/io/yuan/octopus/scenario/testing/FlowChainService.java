package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import io.yuan.octopus.scenario.testing.TestingModel.Flow;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Durable candidate workspace; a model call never confirms a human flow or memory. */
@Service
public class FlowChainService {
    public enum State { QUEUED, RUNNING, PAUSE_REQUESTED, PAUSED, FAILED, READY, ACCEPTING, ACCEPTED, CANCELLED }
    public enum NodeState { WAITING, PREPARING, GENERATING, COMPLETED, FAILED, INTERRUPTED }
    public record Request(String requestId, Flow flow, String environment, String goal, String model, Map<String,Object> contextValues) {}
    public record Confirm(String originalHash, String resultHash) {}
    public record Retry(String nodeId) {}
    public record Context(int sourceCount, List<StepMemoryService.Evidence> sources, List<Map<String,Object>> background, List<String> upstreamNodeIds) {}
    public record Event(long revision,String at,String nodeId,String state,String message,FlowFailure.Detail failure) {}
    public record NodeView(String nodeId, String label, NodeState state, int attempts, String startedAt, String finishedAt, String error,
                           Context context, StepMemoryService.View memory, List<String> assumptions, List<String> questions, List<String> warnings, FlowFailure.Detail failure) {}
    public record View(String id, long revision, State state, Flow original, Flow candidate, String originalHash, String resultHash, String environment,
                       String goal, String model, String createdAt, String updatedAt, String error, List<NodeView> nodes, List<Operation> operations,
                       FlowFailure.Detail failure,List<Event> events) {}
    public record TaskSummary(String id,String flowId,String name,State state,String environment,String updatedAt,int completed,int total,FlowFailure.Detail failure,
                              String createdAt,String model,String currentNodeId,String currentNodeLabel,NodeState currentNodeState) {}
    static final class Node {
        final String id, label; NodeState state = NodeState.WAITING; int attempts; String startedAt, finishedAt, error;
        Flow before; Context context; StepMemoryService.View memory; FlowFailure.Detail failure;
        List<String> assumptions = List.of(), questions = List.of(), warnings = List.of();
        Node(FlowNode node) { id = node.id(); label = node.label(); }
        void reset() { state=NodeState.WAITING; startedAt=null; finishedAt=null; error=null; failure=null; before=null; context=null; memory=null; assumptions=List.of(); questions=List.of(); warnings=List.of(); }
        NodeView view() { return new NodeView(id,label,state,attempts,startedAt,finishedAt,error,context,memory,assumptions,questions,warnings,failure); }
    }
    static final class Run {
        int formatVersion=1;
        final String id = UUID.randomUUID().toString(), requestHash, createdAt = now(); final Request request;
        final List<Node> nodes; final Map<String,Operation> operations = new LinkedHashMap<>(); final List<Event> events=new ArrayList<>();
        Map<String,Object> values; Flow candidate; long revision, leaseUntil; String owner;
        State state = State.QUEUED; String error, updatedAt = createdAt; FlowFailure.Detail failure;
        Run(Request request) {
            this.request=new Request(request.requestId(),request.flow(),request.environment(),request.goal(),request.model(),Map.of()); requestHash=hash(request); values=request.contextValues(); candidate=request.flow();
            nodes=ordered(candidate).stream().map(Node::new).toList(); event(null,"QUEUED","任务已保存，等待开始",null);
        }
        void event(String nodeId,String status,String message,FlowFailure.Detail detail) {
            revision++;updatedAt=now();events.add(new Event(revision,updatedAt,nodeId,status,message,detail));
            while(events.size()>200)events.removeFirst();
        }
        void state(State next,String message) { state=next;event(null,next.name(),message,null); }
        void failed(FlowFailure.Detail detail,State next) { state=next;owner=null;leaseUntil=0;failure=detail;error=detail.message();event(detail.nodeId(),next.name(),detail.message(),detail); }
        List<String> memoryIds() { return nodes.stream().filter(n -> n.state==NodeState.COMPLETED).map(n -> n.memory.id()).toList(); }
    }
    private static final long LEASE_MILLIS=45000;
    private final StepSuggestionService steps;
    private final ChainWorkspaceStore store;
    private final ExecutorService pool;
    private final ScheduledExecutorService supervisor;
    private final Consumer<Runnable> dispatch;
    private final Clock clock;
    private final Map<String,String> dispatched=new ConcurrentHashMap<>();
    private final Map<String,String> workers=new ConcurrentHashMap<>();
    private volatile boolean closed;
    @Autowired
    public FlowChainService(StepSuggestionService steps,JpaChainWorkspaceStore store) {
        this.steps=steps;this.store=store;clock=Clock.systemUTC();
        pool=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),Thread.ofPlatform().name("flow-fill-",0).daemon(true).factory());
        dispatch=pool::execute;
        supervisor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("flow-fill-recovery").daemon(true).factory());
        supervisor.scheduleWithFixedDelay(this::maintenance,5,5,TimeUnit.SECONDS);
    }
    FlowChainService(StepSuggestionService steps,Consumer<Runnable> dispatch,ChainWorkspaceStore store,Clock clock) {
        this.steps=steps;this.dispatch=dispatch;this.store=store;this.clock=clock;pool=null;supervisor=null;
    }
    @PreDestroy public void close() {
        closed=true;if(supervisor!=null)supervisor.shutdownNow();
        workers.forEach((id,token) -> { try { store.update(id,r -> { if(Objects.equals(r.owner,token))interrupt(r); }); } catch(RuntimeException e) { diagnostic(id,"SHUTDOWN",e); } });
        if(pool!=null)pool.shutdownNow();
    }
    public View create(Request input) {
        require(input!=null && input.requestId()!=null && input.requestId().matches("[A-Za-z0-9_-]{16,64}"),"补全任务缺少请求标识");
        require(input.goal()!=null && !input.goal().isBlank() && input.goal().length()<=12000,"请填写整条链路的测试目标");
        ProjectContextService.environment(input.environment());
        FlowFillValidator.validateRequest(new FlowSuggestionService.Request(input.flow(),input.environment(),input.goal(),input.model(),input.contextValues(),Map.of()));
        var run=store.create(new Run(read(canonical(input),Request.class)));
        if(run.state==State.QUEUED)enqueue(run.id);return get(run.id);
    }
    public View get(String id) {
        var run=store.get(id);if(expired(run))run=store.update(id,this::recover);return view(run);
    }
    public View latest(String flowId) { var run=store.latest(flowId);return run==null?null:get(run.id); }
    public List<TaskSummary> recent() { return store.recent(); }
    public List<TaskSummary> tasks() { return store.tasks(); }
    public View pause(String id) { return view(store.update(id,r -> {
        if(r.state==State.QUEUED)r.state(State.PAUSED,"任务已暂停");
        else if(r.state==State.RUNNING)r.state(State.PAUSE_REQUESTED,"当前节点完成后暂停");
        else require(Set.of(State.PAUSED,State.PAUSE_REQUESTED,State.FAILED,State.READY).contains(r.state),"当前任务不能暂停");
    })); }
    public View resume(String id) {
        var run=store.update(id,r -> {
            recover(r);require(r.state==State.PAUSED || r.state==State.FAILED,"任务未暂停或失败，请重新连接查看当前进度");
            r.nodes.stream().filter(n -> Set.of(NodeState.FAILED,NodeState.INTERRUPTED).contains(n.state)).forEach(Node::reset);
            r.error=null;r.failure=null;r.owner=null;r.leaseUntil=0;r.state(State.QUEUED,"从最近检查点继续补全");
        });
        if(workers.containsKey(id))dispatched.remove(id);enqueue(run.id);return get(id);
    }
    public View retry(String id,String nodeId) {
        var run=store.update(id,r -> {
            recover(r);require(Set.of(State.PAUSED,State.FAILED,State.READY).contains(r.state),"请先暂停任务，再从指定节点重新生成");
            int index=-1;for(int i=0;i<r.nodes.size();i++)if(r.nodes.get(i).id.equals(nodeId))index=i;
            require(index>=0 && r.nodes.get(index).before!=null,"该节点尚未开始，无需重新生成");
            r.candidate=r.nodes.get(index).before;for(int i=index;i<r.nodes.size();i++)r.nodes.get(i).reset();
            r.error=null;r.failure=null;r.owner=null;r.leaseUntil=0;r.state=State.QUEUED;r.event(nodeId,"QUEUED","恢复到此节点之前，重新生成后续链路",null);
        });
        if(workers.containsKey(id))dispatched.remove(id);enqueue(run.id);return get(id);
    }
    public View cancel(String id) { return view(store.update(id,r -> {
        require(r.state!=State.ACCEPTING && r.state!=State.ACCEPTED,"已应用或正在应用的任务不能取消");
        r.state(State.CANCELLED,"任务已取消，迟到结果将被丢弃");r.owner=null;r.leaseUntil=0;r.values=Map.of();
        r.nodes.stream().filter(n -> Set.of(NodeState.PREPARING,NodeState.GENERATING).contains(n.state)).forEach(n -> n.state=NodeState.INTERRUPTED);
    })); }
    public View confirm(String id,Confirm receipt) {
        try {
            // Memory confirmation and ACCEPTED checkpoint join one DB transaction. A lost HTTP response is safe to replay.
            return view(store.update(id,r -> {
                require(receipt!=null && hash(r.request.flow()).equals(receipt.originalHash()) && hash(r.candidate).equals(receipt.resultHash()),"待审阅的链路结果已改变，请刷新任务");
                if(r.state==State.ACCEPTED)return;
                require(r.state==State.READY,"只有整条链路完成后才能统一应用");
                List<StepMemoryService.View> confirmed;
                try { confirmed=steps.confirmChain(r.candidate,r.request.environment(),r.memoryIds()); }
                catch(RuntimeException e) { throw FlowFailure.classify(e,"CONFIRMING"); }
                for(var node:r.nodes)node.memory=confirmed.stream().filter(m -> m.nodeId().equals(node.id)).findFirst().orElseThrow();
                r.values=Map.of();r.error=null;r.failure=null;r.state(State.ACCEPTED,"整链建议与步骤记忆已统一确认，可恢复到草稿");
            }));
        } catch(RuntimeException e) {
            if(e instanceof IllegalArgumentException)throw e;
            var detail=FlowFailure.classify(e,"CONFIRMING").detail("CONFIRMING",null);
            try { store.update(id,r -> { if(r.state==State.READY && receipt!=null && hash(r.candidate).equals(receipt.resultHash()))r.failed(detail,State.READY); }); }
            catch(RuntimeException storage) { diagnostic(id,"CONFIRMING",storage); }
            throw e;
        }
    }
    private void enqueue(String id) {
        String ticket=UUID.randomUUID().toString();if(closed || dispatched.putIfAbsent(id,ticket)!=null)return;
        try { dispatch.accept(() -> { try { advance(id); } finally { dispatched.remove(id,ticket); } }); }
        catch(RejectedExecutionException e) {
            dispatched.remove(id,ticket);store.update(id,r -> { if(r.state==State.QUEUED)r.failed(new FlowFailure(FlowFailure.Code.QUEUE_BUSY).detail("QUEUED",null),State.FAILED); });
        }
    }
    private void advance(String id) {
        if(closed)return;String token=UUID.randomUUID().toString();String stage="PREPARING",nodeId=null;
        try {
            var claim=store.update(id,r -> {
                if(r.state!=State.QUEUED)return;r.owner=token;r.leaseUntil=clock.millis()+LEASE_MILLIS;r.state(State.RUNNING,"执行器已接管任务");
            });
            if(!owned(claim,token))return;workers.put(id,token);
            while(!closed) {
                var run=store.update(id,r -> {
                    if(!owned(r,token))return;
                    if(r.state==State.PAUSE_REQUESTED){r.owner=null;r.leaseUntil=0;r.state(State.PAUSED,"当前节点已保存，后续节点未启动");return;}
                    var next=r.nodes.stream().filter(n -> n.state!=NodeState.COMPLETED).findFirst();
                    if(next.isEmpty()){r.owner=null;r.leaseUntil=0;r.state(State.READY,"整条链路的候选建议已保存，等待统一审阅");return;}
                    var n=next.get();n.before=r.candidate;n.state=NodeState.PREPARING;n.attempts++;n.startedAt=now();n.error=null;n.failure=null;
                    r.event(n.id,n.state.name(),"读取当前版本契约、源码与项目背景",null);
                });
                if(!owned(run,token))return;
                var node=run.nodes.stream().filter(n -> n.state==NodeState.PREPARING).findFirst().orElseThrow();nodeId=node.id;stage="PREPARING";
                var prepared=steps.prepare(new StepSuggestionService.Prepare(run.candidate,node.id,run.request.environment()));
                run=store.update(id,r -> {
                    if(!owned(r,token))return;var n=node(r,node.id);
                    n.context=new Context(prepared.sources().size(),prepared.sources().stream().map(s -> new StepMemoryService.Evidence(s.id(),s.projectId(),s.commitId(),s.filePath(),s.lineStart(),s.lineEnd(),s.kind())).toList(),prepared.background(),prepared.upstreamNodeIds());
                    n.warnings=prepared.warnings();n.state=NodeState.GENERATING;r.event(n.id,n.state.name(),"模型正在补全当前节点",null);
                });
                if(!owned(run,token))return;stage="GENERATING";
                var focus=prepared.flow().nodes().stream().filter(n -> n.id().equals(node.id)).findFirst().orElseThrow();
                var operation=prepared.operations().stream().filter(o -> o.ref().equals(focus.operationRef())).findFirst().orElseThrow();
                var editable=operation.inputs().keySet().stream().filter(key -> !focus.inputs().containsKey(key)).toList();
                var result=steps.suggest(new StepSuggestionService.Request(prepared.flow(),node.id,run.request.environment(),run.request.goal(),run.request.model(),run.values,editable),run.memoryIds());
                stage="CHECKPOINT";
                run=store.update(id,r -> {
                    if(!owned(r,token))return;var n=node(r,node.id);
                    r.candidate=result.flow();prepared.operations().forEach(o -> r.operations.put(o.ref(),o));result.operations().forEach(o -> r.operations.put(o.ref(),o));
                    n.memory=result.memory();n.assumptions=result.assumptions();n.questions=result.questions();
                    var warnings=new LinkedHashSet<>(n.warnings);warnings.addAll(result.warnings());n.warnings=List.copyOf(warnings);
                    n.state=NodeState.COMPLETED;n.finishedAt=now();r.event(n.id,n.state.name(),"节点建议与记忆已保存到检查点",null);
                });
                if(!owned(run,token))return;
            }
        } catch(RuntimeException e) {
            var failure=FlowFailure.classify(e,stage).detail(stage,nodeId);diagnostic(id,stage,failure);
            try { store.update(id,r -> {
                if(!owned(r,token))return;
                if(failure.nodeId()!=null){var n=node(r,failure.nodeId());n.state=NodeState.FAILED;n.error=failure.message();n.failure=failure;n.finishedAt=now();}
                r.failed(failure,State.FAILED);
            }); } catch(RuntimeException storage) { diagnostic(id,"CHECKPOINT",storage); }
        } finally { workers.remove(id,token); }
    }
    /** Renewable leases distinguish a live slow model call from a dead process, and fence late responses. */
    void maintenance() {
        if(closed)return;
        workers.forEach((id,token) -> { try { store.update(id,r -> { if(owned(r,token))r.leaseUntil=clock.millis()+LEASE_MILLIS;else recover(r); }); } catch(RuntimeException e) { diagnostic(id,"HEARTBEAT",e); } });
        try {
            for(String id:store.pending()) {
                try { var run=store.update(id,this::recover);if(run.state==State.QUEUED)enqueue(id); }
                catch(RuntimeException e) { diagnostic(id,"RECOVERY",e); }
            }
        } catch(RuntimeException e) { diagnostic("recovery","RECOVERY",e); }
    }
    private boolean owned(Run r,String token) { return Objects.equals(r.owner,token) && active(r.state) && r.leaseUntil>clock.millis(); }
    private boolean expired(Run r) { return (active(r.state)||r.state==State.ACCEPTING) && r.leaseUntil<=clock.millis(); }
    private void recover(Run r) { if(expired(r))interrupt(r); }
    private void interrupt(Run r) {
        var current=r.nodes.stream().filter(n -> Set.of(NodeState.PREPARING,NodeState.GENERATING).contains(n.state)).findFirst();
        var detail=new FlowFailure(FlowFailure.Code.WORKER_INTERRUPTED).detail(current.map(n -> n.state.name()).orElse("RECOVERY"),current.map(n -> n.id).orElse(null));
        current.ifPresent(n -> { n.state=NodeState.INTERRUPTED;n.error=detail.message();n.failure=detail;n.finishedAt=now(); });
        r.failed(detail,r.state==State.ACCEPTING?State.READY:State.PAUSED);
    }
    private static Node node(Run r,String id) { return r.nodes.stream().filter(n -> n.id.equals(id)).findFirst().orElseThrow(); }
    static List<FlowNode> ordered(Flow flow) {
        if (flow.steps()!=null) return flow.nodes();
        var ancestors=FlowFillValidator.ancestors(flow);var remaining=new LinkedHashSet<>(flow.nodes().stream().map(FlowNode::id).toList());var result=new ArrayList<FlowNode>();
        while(!remaining.isEmpty()) {
            var next=flow.nodes().stream().filter(n -> remaining.contains(n.id()) && Collections.disjoint(remaining,ancestors.get(n.id()))).findFirst().orElseThrow(() -> new IllegalArgumentException("流程存在循环依赖"));
            result.add(next);remaining.remove(next.id());
        }
        return result;
    }
    private static boolean active(State state) { return Set.of(State.RUNNING,State.PAUSE_REQUESTED).contains(state); }
    static boolean terminal(State state) { return Set.of(State.ACCEPTED,State.CANCELLED).contains(state); }
    private static String now() { return Instant.now().toString(); }
    static TaskSummary summary(Run r) {
        var current=r.nodes.stream().filter(n -> Set.of(NodeState.PREPARING,NodeState.GENERATING,NodeState.FAILED,NodeState.INTERRUPTED).contains(n.state)).findFirst()
                .orElseGet(() -> r.nodes.stream().filter(n -> n.state==NodeState.WAITING).findFirst().orElse(null));
        return new TaskSummary(r.id,r.request.flow().flowId(),r.request.flow().name(),r.state,r.request.environment(),r.updatedAt,
                (int)r.nodes.stream().filter(n -> n.state==NodeState.COMPLETED).count(),r.nodes.size(),r.failure,r.createdAt,r.request.model(),
                current==null?null:current.id,current==null?null:current.label,current==null?null:current.state);
    }
    private static void diagnostic(String id,String stage,RuntimeException e) { diagnostic(id,stage,FlowFailure.classify(e,stage).detail(stage,null)); }
    private static void diagnostic(String id,String stage,FlowFailure.Detail failure) {
        LoggerFactory.getLogger(FlowChainService.class).warn("Chain task={} stage={} code={} diagnostic={}",id,stage,failure.code(),failure.diagnosticId());
    }
    private View view(Run r) {
        var refs=r.candidate.nodes().stream().map(FlowNode::operationRef).toList();
        return new View(r.id,r.revision,r.state,r.request.flow(),r.candidate,hash(r.request.flow()),hash(r.candidate),r.request.environment(),r.request.goal(),r.request.model(),r.createdAt,r.updatedAt,r.error,
                r.nodes.stream().map(Node::view).toList(),r.operations.values().stream().filter(o -> refs.contains(o.ref())).toList(),r.failure,List.copyOf(r.events));
    }
}
