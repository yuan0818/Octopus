package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

@Service
public class RepositoryAnalysisStore {
    private final RepositoryAnalysisRepository repository;private final ContextCredentialCipher cipher;private final RepositoryAnalysisHistoryRepository history;private final RepositoryBackgroundAdoptionRepository adoptions;
    public RepositoryAnalysisStore(RepositoryAnalysisRepository repository,ContextCredentialCipher cipher,RepositoryAnalysisHistoryRepository history,RepositoryBackgroundAdoptionRepository adoptions){this.repository=repository;this.cipher=cipher;this.history=history;this.adoptions=adoptions;}
    /** Preserve every legacy task; one current workspace per repository/branch/commit. Idempotent. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional public void migrateVersionScopes() {
        var groups=new LinkedHashMap<String,List<RepositoryAnalysisWorkspace>>();
        for(String key:repository.scopeIds().stream().sorted().toList()) {
            var row=repository.locked(key).orElseThrow();var run=decode(row);
            groups.computeIfAbsent(RepositoryAnalysisService.id(run.request),k->new ArrayList<>()).add(row);
        }
        for(var entry:groups.entrySet()) {
            String scope=entry.getKey();var rows=entry.getValue();
            if(rows.size()==1 && rows.getFirst().id.equals(scope))continue;
            var selected=rows.stream().filter(r->r.id.equals(scope)).findFirst().orElseGet(()->rows.stream().max(Comparator.comparing((RepositoryAnalysisWorkspace r)->decode(r).createdAt).thenComparing(r->r.updatedAt).thenComparing(r->r.id)).orElseThrow());
            for(var old:rows)saveHistory(decode(old),old.snapshotEncrypted,false);
            var run=decode(selected);run.taskId=RepositoryAnalysisTasks.taskId(run);
            if(RepositoryAnalysisService.active(run.state)){run.state="FAILED";run.failure=RepositoryAnalysisService.failure(new FlowFailure(FlowFailure.Code.WORKER_INTERRUPTED),"REPOSITORY_ANALYSIS");}
            var row=new RepositoryAnalysisWorkspace();row.id=scope;row.state=run.state;row.updatedAt=Instant.parse(run.updatedAt);row.snapshotEncrypted=cipher.encrypt(canonical(run));
            repository.saveAndFlush(row);saveHistory(run,row.snapshotEncrypted,true);
            for(var old:rows)if(!old.id.equals(scope))repository.delete(old);
        }
        for(String scope:repository.scopeIds())initializeAdoption(scope);
    }
    /** Called after legacy scopes are normalized. Empty markers prevent future automatic adoption. */
    private void initializeAdoption(String scope) {
        if(adoptions.existsById(scope))return;
        var row=new RepositoryBackgroundAdoption();row.id=scope;
        var candidate=summaries().stream().filter(t->RepositoryAnalysisService.id(new RepositoryAnalysisService.Request(t.repositoryKey(),t.branch(),t.commitId(),null,"" )).equals(scope))
                .filter(t->"READY".equals(t.state())).map(t->task(t.taskId()).run()).filter(r->r.report!=null).findFirst().orElse(null);
        if(candidate!=null){row.taskId=RepositoryAnalysisTasks.taskId(candidate);row.adoptedAt=Instant.parse(candidate.updatedAt);}
        adoptions.saveAndFlush(row);
    }
    public record Adopted(RepositoryAnalysisService.Run run,String adoptedAt) {}
    public Adopted adopted(String scope) {
        var row=adoptions.findById(scope).orElse(null);
        if(row==null || row.taskId==null)return null;
        var run=task(row.taskId).run();
        IrValidator.require(RepositoryAnalysisService.id(run.request).equals(scope) && "READY".equals(run.state) && run.report!=null,"已采用背景记录无效");
        return new Adopted(run,row.adoptedAt.toString());
    }
    @Transactional public Adopted adopt(String taskId) {
        var initial=task(taskId).run();String scope=RepositoryAnalysisService.id(initial.request);
        repository.locked(scope).orElseThrow(()->new IllegalArgumentException("仓库背景范围不存在"));
        var run=task(taskId).run();
        IrValidator.require("READY".equals(run.state) && run.report!=null,"只能采用已完成且具有完整报告的分析任务");
        var row=adoptions.findById(scope).orElseGet(RepositoryBackgroundAdoption::new);row.id=scope;
        if(!taskId.equals(row.taskId)){row.taskId=taskId;row.adoptedAt=Instant.now();adoptions.saveAndFlush(row);}
        return new Adopted(run,row.adoptedAt.toString());
    }
    public RepositoryAnalysisService.Run get(String id){return repository.findById(id).map(this::decode).orElse(null);}
    public List<String> pending(){return repository.pending();}
    @Transactional public RepositoryAnalysisService.Run start(RepositoryAnalysisService.Request request) {return startExpected(request,null);}
    @Transactional public RepositoryAnalysisService.Run startExpected(RepositoryAnalysisService.Request request,String expectedTaskId) {
        String id=RepositoryAnalysisService.id(request);var row=repository.locked(id).orElseGet(RepositoryAnalysisWorkspace::new);
        if(expectedTaskId!=null)IrValidator.require(row.id!=null && RepositoryAnalysisTasks.taskId(decode(row)).equals(expectedTaskId),"当前任务已变更，请刷新历史列表");
        initializeAdoption(id);
        if(row.id!=null && RepositoryAnalysisService.active(row.state))return decode(row);
        var previous=row.id==null?null:decode(row);row.id=id;row.owner=null;row.leaseUntil=0;
        var run=!Boolean.TRUE.equals(request.restart()) && previous!=null && previous.state.equals("FAILED")?previous:new RepositoryAnalysisService.Run(request);
        run.previousFailure=run.failure==null?null:run.failure.message();
        if(run==previous) {
            IrValidator.require(request.goal()==null || request.goal().isBlank() || Objects.equals(request.goal(),previous.request.goal()),"恢复任务保留原分析目标；修改目标请重新规划全部分析");
            request=new RepositoryAnalysisService.Request(request.repositoryKey(),request.branch(),request.commitId(),request.environment(),request.model(),request.restart(),previous.request.goal());
        }
        if(previous!=null && run!=previous)saveHistory(previous,row.snapshotEncrypted,false);
        run.request=request;run.state="QUEUED";run.failure=null;write(row,run);return run;
    }
    @Transactional public RepositoryAnalysisService.Run claim(String id,String owner) {
        var row=repository.locked(id).orElseThrow();if(!row.state.equals("QUEUED"))return null;
        var run=decode(row);run.state="COLLECTING";row.owner=owner;row.leaseUntil=System.currentTimeMillis()+45000;write(row,run);return run;
    }
    @Transactional public boolean update(String id,String owner,Consumer<RepositoryAnalysisService.Run> update) {
        var row=repository.locked(id).orElseThrow();
        if(owner!=null && (!owner.equals(row.owner) || row.leaseUntil<System.currentTimeMillis()))return false;
        var run=decode(row);update.accept(run);
        if(!RepositoryAnalysisService.active(run.state)){row.owner=null;row.leaseUntil=0;}
        write(row,run);return true;
    }
    @Transactional public void heartbeat(String id,String owner) {
        var row=repository.locked(id).orElseThrow();if(owner.equals(row.owner) && row.leaseUntil>=System.currentTimeMillis()){row.leaseUntil=System.currentTimeMillis()+45000;repository.save(row);}
    }
    @Transactional public void recover(String id) {
        var row=repository.locked(id).orElseThrow();
        if(RepositoryAnalysisService.active(row.state) && !row.state.equals("QUEUED") && row.leaseUntil<System.currentTimeMillis()) {
            var run=decode(row);run.state="FAILED";run.failure=RepositoryAnalysisService.failure(new FlowFailure(FlowFailure.Code.WORKER_INTERRUPTED),"REPOSITORY_ANALYSIS");row.owner=null;row.leaseUntil=0;write(row,run);
        }
    }
    private void saveHistory(RepositoryAnalysisService.Run run,String snapshot,boolean current) {
        String taskId=RepositoryAnalysisTasks.taskId(run);var row=history.findById(taskId).orElseGet(RepositoryAnalysisHistory::new);
        row.id=taskId;row.scopeId=RepositoryAnalysisService.id(run.request);row.current=current;row.updatedAt=Instant.parse(run.updatedAt);
        row.summaryEncrypted=cipher.encrypt(canonical(RepositoryAnalysisTasks.summary(run,current)));row.snapshotEncrypted=snapshot;history.saveAndFlush(row);
    }
    public List<RepositoryAnalysisTasks.Summary> summaries() {
        var rows=history.metadata();var values=new ArrayList<RepositoryAnalysisTasks.Summary>();var indexedScopes=new HashSet<String>();
        for(var row:rows){values.add(read(cipher.decrypt(row.getSummaryEncrypted()),RepositoryAnalysisTasks.Summary.class));if(row.getCurrent())indexedScopes.add(row.getScopeId());}
        // Legacy current snapshots remain visible without inventing overwritten historical runs.
        for(String scope:repository.scopeIds())if(!indexedScopes.contains(scope)){var run=get(scope);if(run!=null)values.add(RepositoryAnalysisTasks.summary(run,true));}
        return values.stream().sorted(Comparator.comparing(RepositoryAnalysisTasks.Summary::updatedAt).reversed().thenComparing(RepositoryAnalysisTasks.Summary::taskId)).toList();
    }
    public record TaskEntry(RepositoryAnalysisService.Run run,boolean current) {}
    public TaskEntry task(String taskId) {
        IrValidator.require(taskId!=null && taskId.matches("[a-fA-F0-9-]{36,64}"),"任务编号无效");
        var row=history.findById(taskId).orElse(null);
        if(row!=null)return new TaskEntry(read(cipher.decrypt(row.snapshotEncrypted),RepositoryAnalysisService.Run.class),row.current);
        for(String scope:repository.scopeIds()){var run=get(scope);if(run!=null && RepositoryAnalysisTasks.taskId(run).equals(taskId))return new TaskEntry(run,true);}
        // A new run may have archived the previous snapshot while it was being read.
        row=history.findById(taskId).orElseThrow(()->new IllegalArgumentException("分析任务不存在"));
        return new TaskEntry(read(cipher.decrypt(row.snapshotEncrypted),RepositoryAnalysisService.Run.class),row.current);
    }
    private RepositoryAnalysisService.Run decode(RepositoryAnalysisWorkspace row) {
        try{return read(cipher.decrypt(row.snapshotEncrypted),RepositoryAnalysisService.Run.class);}catch(RuntimeException e){throw new FlowFailure(FlowFailure.Code.STORAGE_UNAVAILABLE);}
    }
    private void write(RepositoryAnalysisWorkspace row,RepositoryAnalysisService.Run run) {
        if(!cipher.available())throw new FlowFailure(FlowFailure.Code.STORAGE_UNAVAILABLE);
        run.updatedAt=Instant.now().toString();row.updatedAt=Instant.parse(run.updatedAt);row.state=run.state;row.snapshotEncrypted=cipher.encrypt(canonical(run));repository.saveAndFlush(row);saveHistory(run,row.snapshotEncrypted,true);
    }
}
