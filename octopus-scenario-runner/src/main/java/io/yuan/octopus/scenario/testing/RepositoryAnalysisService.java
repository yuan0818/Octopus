package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class RepositoryAnalysisService {
    public record Request(String repositoryKey,String branch,String commitId,@com.fasterxml.jackson.annotation.JsonIgnore String environment,String model,Boolean restart,String goal) {
        public Request(String repositoryKey,String branch,String commitId,String environment,String model,Boolean restart){this(repositoryKey,branch,commitId,environment,model,restart,null);}
        public Request(String repositoryKey,String branch,String commitId,String environment,String model){this(repositoryKey,branch,commitId,environment,model,false);}
    }
    public record ModuleRole(String modulePath,String role,List<String> evidenceIds) {}
    public record Rule(String title,String kind,String description,List<String> modulePaths,List<String> clientPreparation,List<String> evidenceIds,List<String> uncertainties) {}
    public record Report(String summary,List<ModuleRole> modules,List<Rule> rules,List<String> questions) {}
    public record View(String id,String state,Request request,String model,String createdAt,String updatedAt,Report report,List<RepositoryCatalogClient.SourceEvidence> sources,List<String> warnings,FlowFailure.Detail failure,RepositoryAnalysisWorkflow.Progress progress,String taskId) {
        public View(String id,String state,Request request,String model,String createdAt,String updatedAt,Report report,List<RepositoryCatalogClient.SourceEvidence> sources,List<String> warnings,FlowFailure.Detail failure,RepositoryAnalysisWorkflow.Progress progress){this(id,state,request,model,createdAt,updatedAt,report,sources,warnings,failure,progress,null);}
        public View(String id,String state,Request request,String model,String createdAt,String updatedAt,Report report,List<RepositoryCatalogClient.SourceEvidence> sources,List<String> warnings,FlowFailure.Detail failure){this(id,state,request,model,createdAt,updatedAt,report,sources,warnings,failure,null);}
    }
    static final class Run {
        String taskId=UUID.randomUUID().toString();Request request;String state="QUEUED",model,createdAt=Instant.now().toString(),updatedAt=createdAt;
        RepositoryCatalogClient.BackgroundPacket packet;Report report;FlowFailure.Detail failure;
        RepositoryCatalogClient.Inventory inventory; RepositoryAnalysisWorkflow.Plan plan;
        int synthesisAttempts; List<RepositoryAnalysisWorkflow.TaskRun> tasks; String currentTaskId,previousFailure; List<String> summarizedTaskIds;
        Run(Request request){this.request=request;}
    }
    private final RepositoryAnalysisStore store;private final RepositoryCatalogClient catalog;private final FlowModelClient llm;
    private final ExecutorService pool=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),Thread.ofPlatform().name("repository-background-",0).daemon(true).factory());
    private final ScheduledExecutorService supervisor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("repository-background-recovery").daemon(true).factory());
    private final Set<String> queued=ConcurrentHashMap.newKeySet();private final Map<String,String> workers=new ConcurrentHashMap<>();
    private volatile boolean closed;
    public RepositoryAnalysisService(RepositoryAnalysisStore store,RepositoryCatalogClient catalog,FlowModelClient llm) {
        this.store=store;this.catalog=catalog;this.llm=llm;supervisor.scheduleWithFixedDelay(this::maintenance,5,5,TimeUnit.SECONDS);
    }
    static String id(Request r){return hash(List.of(r.repositoryKey(),r.branch(),r.commitId()));}
    static boolean active(String state){return Set.of("QUEUED","COLLECTING","PLANNING","ANALYZING","SYNTHESIZING","SUMMARIZING").contains(state);}
    private static void validate(Request r) {
        require(r!=null && r.repositoryKey()!=null && !r.repositoryKey().isBlank() && r.repositoryKey().length()<=1000 && r.branch()!=null && !r.branch().isBlank() && r.branch().length()<=200 && r.commitId()!=null && r.commitId().matches("[a-fA-F0-9]{40}"),"请选择仓库、分支和已分析提交");
        require(r.goal()==null || r.goal().length()<=6000,"分析目标最多 6000 字符");
    }
    public View get(Request request){validate(request);return view(store.get(id(request)));}
    public record AdoptedBackground(View analysis,String adoptedAt) {}
    public AdoptedBackground adopted(Request request){validate(request);var a=store.adopted(id(request));return a==null?null:new AdoptedBackground(view(a.run()),a.adoptedAt());}
    public AdoptedBackground adoptTask(String taskId){var a=store.adopt(taskId);return new AdoptedBackground(view(a.run()),a.adoptedAt());}
    public View start(Request request) {return start(request,null);}
    private View start(Request request,String expectedTaskId) {
        validate(request);require(catalog.versions(request.repositoryKey(),request.branch()).stream().anyMatch(v->v.commitId().equals(request.commitId())),"该仓库分支尚未分析此提交");
        Run run;try{run=store.startExpected(request,expectedTaskId);}catch(org.springframework.dao.DataIntegrityViolationException e){run=store.get(id(request));if(run==null)throw e;}
        enqueue(id(request));return view(run);
    }
    public View cancel(Request request) {validate(request);store.update(id(request),null,r->{require(active(r.state),"任务当前不能取消");r.state="CANCELLED";});return get(request);}
    public View reference(String key,String branch,String commit,String environment) {
        var adopted=store.adopted(id(new Request(key,branch,commit,environment,"")));
        if(adopted!=null)return view(adopted.run());
        if(store.get(id(new Request(key,branch,commit,environment,"")))!=null)
            throw new FlowFailure(FlowFailure.Code.CONTEXT_UNAVAILABLE,null,"该版本尚无已采用背景，请在背景分析或任务历史中采用已完成的结论后再补全步骤");
        return null;
    }

    public RepositoryAnalysisTasks.Page tasks(String repositoryKey,String modulePath,String branch,String commit,String environment,String state,String search,int page,int pageSize) {
        require(page>=1 && pageSize>=1 && pageSize<=100,"分页范围无效");
        var filtered=store.summaries().stream().filter(t->blank(repositoryKey)||t.repositoryKey().equals(repositoryKey))
            .filter(t->blank(modulePath)||t.modulePaths().contains(modulePath)).filter(t->blank(branch)||t.branch().equals(branch))
            .filter(t->blank(commit)||t.commitId().equals(commit))
            .filter(t->blank(state)||t.state().equals(state)).filter(t->blank(search)||(Objects.toString(t.repositoryName(),"")+" "+Objects.toString(t.goal(),"")+" "+t.commitId()).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).toList();
        return new RepositoryAnalysisTasks.Page(filtered.stream().skip((long)(page-1)*pageSize).limit(pageSize).toList(),filtered.size(),page,pageSize);
    }
    private static boolean blank(String value){return value==null || value.isBlank();}
    public RepositoryAnalysisTasks.Detail task(String taskId) {var entry=store.task(taskId);var adopted=store.adopted(id(entry.run().request));return new RepositoryAnalysisTasks.Detail(taskId,entry.current(),view(entry.run()),adopted!=null && RepositoryAnalysisTasks.taskId(adopted.run()).equals(taskId),adopted!=null && RepositoryAnalysisTasks.taskId(adopted.run()).equals(taskId)?adopted.adoptedAt():null);}
    public View resumeTask(String taskId) {var entry=store.task(taskId);require(entry.current() && entry.run().state.equals("FAILED"),"只能恢复当前失败任务，历史记录只读");var r=entry.run().request;return start(new Request(r.repositoryKey(),r.branch(),r.commitId(),r.environment(),r.model(),false,r.goal()),taskId);}
    public View cancelTask(String taskId) {var entry=store.task(taskId);require(entry.current(),"历史记录只读");store.update(id(entry.run().request),null,r->{require(RepositoryAnalysisTasks.taskId(r).equals(taskId) && active(r.state),"任务已变更或已结束，请刷新");r.state="CANCELLED";});return view(store.task(taskId).run());}
    private void enqueue(String id) {
        if(closed || !queued.add(id))return;
        try{pool.execute(()->{try{work(id);}finally{queued.remove(id);}});}catch(RejectedExecutionException e){queued.remove(id);}
    }
    private void work(String id) {
        String owner=UUID.randomUUID().toString();Run run=store.claim(id,owner);if(run==null)return;workers.put(id,owner);
        try { new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,owner); }
        finally { workers.remove(id,owner); }
    }
    static RepositoryCatalogClient.BackgroundPacket modelPacket(RepositoryCatalogClient.BackgroundPacket packet) {
        var sources=new ArrayList<RepositoryCatalogClient.SourceEvidence>();
        for(int i=0;i<packet.sources().size();i++){var s=packet.sources().get(i);sources.add(new RepositoryCatalogClient.SourceEvidence("S"+(i+1),s.projectId(),s.commitId(),s.filePath(),s.lineStart(),s.lineEnd(),s.kind(),s.text(),s.truncated()));}
        return new RepositoryCatalogClient.BackgroundPacket(packet.repositoryKey(),packet.name(),packet.branch(),packet.commitId(),packet.environment(),packet.modules(),sources,packet.warnings());
    }
    static Report restoreReferences(Report report,RepositoryCatalogClient.BackgroundPacket packet) {
        var refs=new HashMap<String,String>();for(int i=0;i<packet.sources().size();i++)refs.put("S"+(i+1),packet.sources().get(i).id());
        return mapReferences(report,refs);
    }
    static Report mapReferences(Report report,Map<String,String> refs) {
        java.util.function.Function<List<String>,List<String>> map=ids->ids.stream().map(refs::get).distinct().toList();
        return new Report(report.summary(),report.modules().stream().map(m->new ModuleRole(m.modulePath(),m.role(),map.apply(m.evidenceIds()))).toList(),report.rules().stream().map(r->new Rule(r.title(),r.kind(),r.description(),r.modulePaths(),r.clientPreparation(),map.apply(r.evidenceIds()),r.uncertainties())).toList(),report.questions());
    }
    /** Only fixed schema paths, numeric indices/counts and static instructions enter this diagnostic. */
    static final class ReportValidationException extends IllegalArgumentException {
        private ReportValidationException(String message){super(message);}
    }
    static void reportRequire(boolean valid,String message){if(!valid)throw new ReportValidationException(message);}
    static void validateReport(Report r,RepositoryCatalogClient.BackgroundPacket packet) {
        reportRequire(r!=null,"report 不能为空");
        reportRequire(r.summary()!=null && !r.summary().isBlank(),"report.summary 不能为空");
        reportRequire(r.summary().length()<=4000,"report.summary 共 "+r.summary().length()+" 字符，最多允许 4000 字符");
        reportRequire(r.modules()!=null,"report.modules 缺少列表");reportRequire(r.modules().size()<=64,"report.modules 共 "+r.modules().size()+" 项，最多允许 64 项");
        reportRequire(r.rules()!=null,"report.rules 缺少列表");reportRequire(r.rules().size()<=40,"report.rules 共 "+r.rules().size()+" 项，最多允许 40 项，请合并相关规则");
        var modules=packet.modules().stream().map(RepositoryCatalogClient.Module::modulePath).toList();var evidence=packet.sources().stream().filter(s->!s.kind().equals("UNSUPPORTED") && !s.text().isBlank()).map(RepositoryCatalogClient.SourceEvidence::id).toList();
        lines(r.questions(),30,"report.questions");
        for(int i=0;i<r.modules().size();i++) {
            var m=r.modules().get(i);String field="report.modules["+i+"]";
            reportRequire(m!=null,field+" 不能为空");reportRequire(modules.contains(m.modulePath()),field+".modulePath 不在当前仓库模块列表中");
            lines(Collections.singletonList(m.role()),1,field+".role");refs(m.evidenceIds(),evidence,field+".evidenceIds");
        }
        for(int i=0;i<r.rules().size();i++) {
            var rule=r.rules().get(i);String field="report.rules["+i+"]";
            reportRequire(rule!=null,field+" 不能为空");reportRequire(rule.kind()!=null && rule.kind().matches("[A-Z][A-Z0-9_]{0,39}"),field+".kind 应为大写英文类别，最多 40 字符");
            reportRequire(rule.modulePaths()!=null && !rule.modulePaths().isEmpty() && modules.containsAll(rule.modulePaths()),field+".modulePaths 必须引用当前仓库的已有模块");
            lines(Arrays.asList(rule.title(),rule.description()),2,field+".title/description");lines(rule.clientPreparation(),12,field+".clientPreparation");lines(rule.uncertainties(),12,field+".uncertainties");
            refs(rule.evidenceIds(),evidence,field+".evidenceIds");reportRequire(!rule.evidenceIds().isEmpty(),field+".evidenceIds 规则缺少证据");
        }
        reportRequire(canonical(r).length()<=80000,"report 共 "+canonical(r).length()+" 字符，最多允许 80000 字符");
    }
    private static void refs(List<String> ids,List<String> allowed,String field){
        reportRequire(ids!=null,field+" 缺少源码引用列表");reportRequire(ids.size()<=64,field+" 共 "+ids.size()+" 项，最多允许 64 项");
        for(int i=0;i<ids.size();i++)reportRequire(allowed.contains(ids.get(i)),field+"["+i+"] 引用未知源码，请使用本次提供的证据编号");
    }
    private static void lines(List<String> lines,int max,String field){
        reportRequire(lines!=null,field+" 缺少列表");reportRequire(lines.size()<=max,field+" 共 "+lines.size()+" 项，最多允许 "+max+" 项，请合并相关内容");
        for(int i=0;i<lines.size();i++){String value=lines.get(i);reportRequire(value!=null,field+"["+i+"] 不能为空");reportRequire(value.length()<=2000,field+"["+i+"] 共 "+value.length()+" 字符，最多允许 2000 字符，请归纳精简");}
    }
    private void maintenance() {
        if(closed)return;
        workers.forEach((id,owner)->{try{store.heartbeat(id,owner);}catch(RuntimeException ignored){}});
        try{for(String id:store.pending()){store.recover(id);var run=store.get(id);if(run!=null && run.state.equals("QUEUED"))enqueue(id);}}catch(RuntimeException ignored){}
    }
    @PreDestroy public void close() {
        closed=true;supervisor.shutdownNow();workers.forEach((id,owner)->{try{store.update(id,owner,r->{r.state="FAILED";r.failure=failure(new FlowFailure(FlowFailure.Code.WORKER_INTERRUPTED),"REPOSITORY_ANALYSIS");});}catch(RuntimeException ignored){}});pool.shutdownNow();
    }
    static Report safeReport(Report r) {
        if(r==null)return null;
        return new Report(r.summary(),r.modules(),r.rules().stream().map(v->new Rule(v.title(),v.kind(),v.description(),v.modulePaths(),v.clientPreparation(),v.evidenceIds(),v.uncertainties().stream().map(ModelKnowledgePolicy::question).distinct().toList())).toList(),r.questions().stream().map(ModelKnowledgePolicy::question).distinct().toList());
    }
    static FlowFailure.Detail failure(Throwable error,String stage) {
        var classified=FlowFailure.classify(error,stage);
        if(classified.code==FlowFailure.Code.UNKNOWN && stage.equals("REPOSITORY_SOURCE"))classified=new FlowFailure(FlowFailure.Code.CONTEXT_UNAVAILABLE);
        var d=classified.detail(stage,null);
        String message=switch(classified.code) {
            case WORKER_INTERRUPTED -> "仓库分析进程中断或失去租约，已保存失败状态";
            case MODEL_TIMEOUT -> "仓库背景归纳超时，本次尚未产生可用结果";
            case UNKNOWN -> "仓库分析发生未分类错误，已保存失败状态";
            default -> d.message();
        };
        String suggestion=switch(classified.code) {
            case MODEL_AUTH,MODEL_CONFIG -> "在 Agent 配置中检查所选模型的配置和权限，修复后重试仓库分析。";
            case CONTEXT_UNAVAILABLE -> "检查此分支提交的代码分析与源码服务，确认后重试仓库分析。";
            case STORAGE_UNAVAILABLE -> "等待数据库恢复后刷新状态，以服务端已保存结果为准。";
            case MODEL_RATE_LIMIT -> "等待限流解除或检查模型额度后重试仓库分析。";
            case MODEL_RESPONSE_INVALID -> "当前阶段的格式修正次数已用完。点击从检查点继续；已读取源码及已完成分项记忆会复用，无需重新规划。";
            case WORKER_INTERRUPTED -> "点击重试仓库分析。旧进程的迟到结果不会覆盖新任务；中断前的模型调用可能已产生费用。";
            default -> "可重试仓库分析；持续失败时请提供诊断编号排查。";
        };
        return new FlowFailure.Detail(d.code(),d.stage(),null,message,suggestion,d.retryable(),d.upstreamStatus(),d.diagnosticId(),d.occurredAt());
    }
    private static View view(Run r){return r==null?null:new View(id(r.request),r.state,r.request,r.model,r.createdAt,r.updatedAt,safeReport(r.report),r.packet==null?List.of():r.packet.sources(),r.packet==null?List.of():r.packet.warnings(),r.failure,RepositoryAnalysisWorkflow.progress(r),RepositoryAnalysisTasks.taskId(r));}
}
