package io.yuan.octopus.scenario.testing;

import java.util.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.RepositoryAnalysisService.*;

/** Checkpointed plan -> model-directed reads -> per-task memory -> whole-repository synthesis. */
public final class RepositoryAnalysisWorkflow {
    public record Task(String id,String title,List<String> modulePaths,String objective,List<String> dependsOn,String configurationObjective) {
        public Task(String id,String title,List<String> modulePaths,String objective,List<String> dependsOn){this(id,title,modulePaths,objective,dependsOn,"查明配置来源、加载与覆盖、动态刷新及对模块行为的影响");}
    }
    public record Plan(String summary,List<Task> tasks) {}
    public record Decision(String action,List<String> files,String prefix,String query,Integer offset,Report memory) {}
    public record Synthesis(List<String> coveredTaskIds,Report report) {}
    static final class TaskRun {
        Task task; String state="WAITING",error,model,toolFeedback,activity; List<String> pendingFiles=List.of(); int rounds,attempts;
        List<RepositoryCatalogClient.SourceEvidence> sources=new ArrayList<>();
        Map<String,Object> filePage; List<Map<String,Object>> toolHistory=new ArrayList<>(); Report memory;
        TaskRun(Task task){this.task=task;}
    }
    public record TaskView(Task task,String state,int rounds,int attempts,String model,String error,Report memory,int sourceCount,String activity,List<String> pendingFiles,List<String> sourceFiles,List<Map<String,Object>> toolHistory,String toolFeedback) {}
    public record Progress(String summary,List<TaskView> tasks,String currentTaskId,List<String> summarizedTaskIds,int synthesisAttempts) {}
    private final RepositoryAnalysisStore store; private final RepositoryCatalogClient catalog; private final FlowModelClient llm;
    RepositoryAnalysisWorkflow(RepositoryAnalysisStore store,RepositoryCatalogClient catalog,FlowModelClient llm){this.store=store;this.catalog=catalog;this.llm=llm;}

    void execute(String id,String owner) {
        Run run=store.get(id);String stage="REPOSITORY_SOURCE";
        try {
            if(run.inventory==null) {
                var r=run.request;var index=catalog.inventory(r.repositoryKey(),r.branch(),r.commitId(),r.environment());
                require(index.repositoryKey().equals(r.repositoryKey()) && index.branch().equals(r.branch()) && index.commitId().equals(r.commitId()),"仓库目录版本不匹配");
                require(!index.modules().isEmpty() && index.modules().size()<=64 && index.files()!=null && index.files().size()<=30000,"目录范围超出编排限制");
                if(!store.update(id,owner,current->{current.inventory=index;current.packet=packet(index,List.of());current.state="PLANNING";}))return;
                run=store.get(id);
            }
            stage="REPOSITORY_PLANNING";
            if(run.plan==null) {
                if(!store.update(id,owner,r->r.state="PLANNING"))return;
                var planContext=new LinkedHashMap<String,Object>();planContext.put("stage","PLAN");planContext.put("goal",Objects.toString(run.request.goal(),""));planContext.put("repository",overview(run.inventory));planContext.put("previousFailure",Objects.toString(run.previousFailure,""));
                planContext.put("allowedModulePaths",run.inventory.modules().stream().map(RepositoryCatalogClient.Module::modulePath).toList());
                planContext.put("planConstraints",Map.of("maxTasks",32,"moduleAssignment","modulePaths 只能原样复制 allowedModulePaths；每个模块恰好分配一次。目录、模块展示名称和 projectId 都不是 modulePath。不能补写、缩写或创建父级聚合模块。",
                    "rootConfiguration","根目录公共文件可在任一相关任务中取证；仅当 allowedModulePaths 中包含根模块标识时才分配根模块，保留标识原样（包括空字符串）。",
                    "correction","previousFailure 指明上一轮错误的字段位置；依据完整清单重新输出全部计划，不仅输出错误项。"));
                for(int attempt=0;attempt<3;attempt++) {
                    var response=llm.repositoryStage(run.request.model(),planContext);
                    try {
                        var plan=parse(response,Plan.class);validatePlan(plan,run.inventory);
                        if(!store.update(id,owner,r->{r.plan=plan;r.model=response.model();r.previousFailure=null;r.tasks=new ArrayList<>(plan.tasks().stream().map(TaskRun::new).toList());r.state="ANALYZING";}))return;
                        break;
                    } catch(ReportValidationException invalid) {
                        planContext.put("previousFailure",invalid.getMessage());
                        if(!store.update(id,owner,r->r.previousFailure=invalid.getMessage()))return;
                        if(attempt==2)throw invalid;
                    } catch(FlowFailure invalid) {
                        if(invalid.code!=FlowFailure.Code.MODEL_RESPONSE_INVALID)throw invalid;
                        planContext.put("previousFailure",invalid.getMessage());
                        if(!store.update(id,owner,r->r.previousFailure=invalid.getMessage()))return;
                        if(attempt==2)throw invalid;
                    }
                }
            }
            run=store.get(id);
            for(int index=0;index<run.tasks.size();index++) {
                final int position=index;
                if(run.tasks.get(index).memory!=null)continue;
                stage="REPOSITORY_MODULE";
                int corrections=0, formatCorrections=0, toolCorrections=0;
                if(!store.update(id,owner,r->{r.currentTaskId=r.tasks.get(position).task.id();r.state="ANALYZING";r.tasks.get(position).state="ANALYZING";if(r.tasks.get(position).toolHistory==null)r.tasks.get(position).toolHistory=new ArrayList<>();}))return;
                while(true) {
                    run=store.get(id);var task=run.tasks.get(position);
                    boolean summarizeOnly=task.rounds>=12 || task.sources.size()>=24 || task.sources.stream().mapToInt(s->s.text().length()).sum()>=96000;
                    var context=new LinkedHashMap<String,Object>();context.put("stage","ANALYZE");context.put("goal",Objects.toString(run.request.goal(),""));context.put("repository",overview(run.inventory));context.put("task",task.task);
                    context.put("filePage",task.filePage==null?filePage(run.inventory,task.task,"","",0,true):task.filePage);
                    var modelSources=modelPacket(packet(run.inventory,task.sources)).sources();
                    context.put("sources",modelSources);
                    context.put("allowedEvidenceIds",modelSources.stream().filter(source->!source.kind().equals("UNSUPPORTED") && !source.text().isBlank()).map(RepositoryCatalogClient.SourceEvidence::id).toList());
                    context.put("evidenceIndex",modelSources.stream().map(source->Map.of("id",source.id(),"filePath",source.filePath(),"usable",!source.kind().equals("UNSUPPORTED") && !source.text().isBlank())).toList());
                    context.put("remainingRounds",Math.max(0,12-task.rounds));context.put("allowedActions",summarizeOnly?List.of("DONE"):List.of("LIST","READ","DONE"));
                    context.put("toolHistory",task.toolHistory);context.put("remainingSourceCharacters",96000-task.sources.stream().mapToInt(s->s.text().length()).sum());
                    context.put("remainingFiles",24-task.sources.size());context.put("memoryCharacterBudget",4500);
                    context.put("toolConstraints",Map.of("maxFilesPerRead",6,"uniqueFiles",true,"pathSource","从 filePage 或之前 LIST 的结果复制精确相对路径；找不到时先 LIST，不猜测路径。","rejectedActions","toolHistory 中 rejected=true 的操作未执行；根据 issues 修正，不能当作已读取证据。"));
                    context.put("previousFailure",Objects.toString(task.error,""));
                    context.put("toolFeedback",Objects.toString(task.toolFeedback,""));
                    context.put("upstreamMemories",run.tasks.stream().filter(t->t.memory!=null && task.task.dependsOn().contains(t.task.id())).map(t->Map.of("taskId",t.task.id(),"memory",t.memory)).toList());
                    if(!store.update(id,owner,r->{var t=r.tasks.get(position);t.attempts++;t.activity=summarizeOnly?"SUMMARIZING":"GENERATING";t.pendingFiles=List.of();}))return;
                    var response=llm.repositoryStage(run.request.model(),context);
                    Decision decision;
                    try {decision=parse(response,Decision.class);}
                    catch(FlowFailure invalid) {
                        if(invalid.code!=FlowFailure.Code.MODEL_RESPONSE_INVALID)throw invalid;
                        String feedback=invalid.getMessage()+"。请按当前阶段结构重新输出一个完整 JSON 对象；DONE 必须包含 memory，evidenceIds 只能来自 allowedEvidenceIds。";
                        int repair=++formatCorrections;
                        if(!store.update(id,owner,r->{var t=r.tasks.get(position);t.model=response==null?null:response.model();t.error=feedback;t.toolFeedback="格式修正 "+Math.min(repair,2)+"/2："+feedback;}))return;
                        if(repair>2)throw invalid;
                        continue;
                    }
                    var issues=toolIssues(decision,run.inventory);
                    if(!issues.isEmpty()) {
                        int correction=++toolCorrections;
                        String action=decision==null?"UNKNOWN":Objects.toString(decision.action(),"UNKNOWN");
                        String feedback="工具请求未执行："+String.join("；",issues)+"。请从 filePage 的精确路径重新选择，每次 1 至 6 个且不重复；找不到文件先 LIST。";
                        if(!store.update(id,owner,r->{var t=r.tasks.get(position);t.model=response.model();t.error=feedback;t.toolFeedback=correction<=2?"自动修正 "+correction+"/2："+feedback:"自动修正次数已用完："+feedback;t.rounds++;
                            t.toolHistory.add(Map.of("action",Set.of("READ","LIST","DONE").contains(action)?action:"UNKNOWN","rejected",true,"issues",issues,"at",java.time.Instant.now().toString()));}))return;
                        if(correction>2)throw new FlowFailure(FlowFailure.Code.MODEL_PATCH_INVALID,null,feedback);
                        continue;
                    }
                    if(summarizeOnly && !decision.action().equals("DONE")) {
                        require(corrections++<2,"取证预算已耗尽，模型仍未提交总结；可从检查点重试当前项的总结");
                        if(!store.update(id,owner,r->r.tasks.get(position).toolFeedback="取证已结束，当前仅允许 DONE。总结已有 sources；缺少证据的模块必须标注未知，不再查询或读取文件。"))return;
                        continue;
                    }
                    if(decision.action().equals("DONE")) {
                        if(!store.update(id,owner,r->r.tasks.get(position).activity="VALIDATING"))return;
                        Report saved;
                        try {
                            var memory=decision.memory();validateReport(memory,modelPacket(packet(run.inventory,task.sources)));
                            require(modulePaths(memory).equals(new TreeSet<>(task.task.modulePaths())) && memory.modules().size()==task.task.modulePaths().size(),"分项记忆必须包含全部分配模块");
                            memory=restoreReferences(memory,packet(run.inventory,task.sources));
                            require(canonical(memory).length()<=6000,"分项记忆过长，请将整个 memory JSON 压缩至 4500 字符以内，保留全部模块和关键证据，合并重复规则");
                            saved=safeReport(memory);
                        } catch(IllegalArgumentException invalid) {
                            if(corrections++>=2)throw invalid;
                            String feedback="记忆未保存："+invalid.getMessage()+"。请根据已有 sources 修正后再次提交 DONE；evidenceIds 只可使用 allowedEvidenceIds 中的编号。UNSUPPORTED 和空源码不可引用；缺少证据的模块用空 evidenceIds 并说明未知，不要捏造规则。";
                            if(!store.update(id,owner,r->{var t=r.tasks.get(position);t.toolFeedback=feedback;t.error=feedback;t.rounds++;}))return;
                            continue;
                        }
                        if(!store.update(id,owner,r->{var t=r.tasks.get(position);t.memory=saved;t.model=response.model();t.state="COMPLETED";t.activity="COMPLETED";t.pendingFiles=List.of();t.error=null;t.rounds++;r.packet=packet(r.inventory,allSources(r));}))return;
                        break;
                    } else if(decision.action().equals("READ")) {
                        var old=task.sources.stream().map(RepositoryCatalogClient.SourceEvidence::filePath).toList();
                        var fresh=decision.files().stream().filter(f->!old.contains(f)).toList();
                        if(fresh.isEmpty() || old.size()+fresh.size()>24) {
                            String feedback=fresh.isEmpty()?"所选文件已经读取，内容都在 sources 中，请直接使用已有证据或读取其他文件。":"本分析项最多二十四份文件，请减少本次文件数量，或基于已有证据完成记忆并写明缺口。";
                            if(!store.update(id,owner,r->{var t=r.tasks.get(position);t.toolFeedback=feedback;t.rounds++;}))return;
                            continue;
                        }
                        if(!store.update(id,owner,r->{var t=r.tasks.get(position);t.activity="READING";t.pendingFiles=List.copyOf(fresh);}))return;
                        var sources=catalog.readFiles(run.request,fresh);
                        require(sources.size()==fresh.size() && sources.stream().map(RepositoryCatalogClient.SourceEvidence::filePath).toList().equals(fresh),"源码读取结果与请求文件不一致");
                        String commit=run.request.commitId();var projects=run.inventory.modules().stream().map(RepositoryCatalogClient.Module::projectId).toList();
                        require(sources.stream().allMatch(s->s.commitId().equals(commit) && projects.contains(s.projectId())),"源码不属于当前仓库版本");
                        if(task.sources.stream().mapToInt(s->s.text().length()).sum()+sources.stream().mapToInt(s->s.text().length()).sum()>96000) {
                            if(!store.update(id,owner,r->{var t=r.tasks.get(position);t.toolFeedback="本次文件超出剩余源码预算，尚未加入 sources。请减少文件数量或用已有证据总结并标明缺口。";t.rounds++;}))return;
                            continue;
                        }
                        if(!store.update(id,owner,r->{var t=r.tasks.get(position);t.error=null;t.sources.addAll(sources);t.toolHistory.add(Map.of("action","READ","files",fresh,"at",java.time.Instant.now().toString()));t.toolFeedback="已读取新文件；重复文件复用 sources 中的已有证据。";t.rounds++;}))return;
                    } else if(decision.action().equals("LIST")) {
                        var page=filePage(run.inventory,task.task,decision.prefix(),decision.query(),decision.offset()==null?0:decision.offset(),false);
                        if(!store.update(id,owner,r->{var t=r.tasks.get(position);t.toolFeedback=canonical(page).equals(canonical(t.filePage))?"本次查询与上次相同，请读取已列出的文件或换用新的查询条件；不要重复相同查询。":"目录查询已完成，文件名见 filePage，查询记录见 toolHistory。";t.error=null;t.filePage=page;t.toolHistory.add(Map.of("action","LIST","prefix",Objects.toString(decision.prefix(),""),"query",Objects.toString(decision.query(),""),"offset",page.get("offset"),"total",page.get("total"),"at",java.time.Instant.now().toString()));t.rounds++;}))return;
                    } else throw new IllegalArgumentException("未知分析动作");
                }
                run=store.get(id);
            }
            stage="REPOSITORY_SYNTHESIS";run=store.get(id);
            require(run.tasks.stream().allMatch(t->t.memory!=null),"全部分项记忆就绪后才能总结归纳");
            if(!store.update(id,owner,r->{r.currentTaskId=null;r.state="SYNTHESIZING";}))return;
            var context=new LinkedHashMap<String,Object>();context.put("stage","SYNTHESIZE");context.put("goal",Objects.toString(run.request.goal(),""));context.put("repository",overview(run.inventory));
            context.put("previousFailure",Objects.toString(run.previousFailure,""));
            context.put("outputCharacterBudget",Math.max(10000,run.inventory.modules().size()*180+6000));
            context.put("reportLimits",Map.of("summaryCharacters",4000,"modules",64,"rules",40,"questions",30,"clientPreparationPerRule",12,"uncertaintiesPerRule",12,"textFieldCharacters",2000,"evidenceIdsPerItem",64,"totalCharacters",80000));
            context.put("consolidationInstructions","各分项记忆可能有重复问题。按同一主题合并为最多 30 条 questions，合并时保留所有未解决事项、适用模块和冲突；不要逐项照抄，也不要为通过数量校验删除未知信息。输出前核对 reportLimits、expectedTaskIds 和全部模块覆盖。");
            var expected=run.tasks.stream().map(t->t.task.id()).toList();context.put("expectedTaskIds",expected);
            var synthesisPacket=packet(run.inventory,allSources(run));var sourceAliases=new LinkedHashMap<String,String>();
            for(int i=0;i<synthesisPacket.sources().size();i++)sourceAliases.put(synthesisPacket.sources().get(i).id(),"S"+(i+1));
            context.put("memories",run.tasks.stream().map(t->Map.of("task",t.task,"memory",mapReferences(t.memory,sourceAliases))).toList());
            context.put("allowedEvidenceIds",modelPacket(synthesisPacket).sources().stream().filter(s->!s.kind().equals("UNSUPPORTED") && !s.text().isBlank()).map(RepositoryCatalogClient.SourceEvidence::id).toList());
            context.put("requiredFields",Map.of("root",List.of("coveredTaskIds","report"),"report",List.of("summary","modules","rules","questions"),"module",List.of("modulePath","role","evidenceIds"),"rule",List.of("title","kind","description","modulePaths","clientPreparation","evidenceIds","uncertainties")));
            context.put("evidenceInstructions","本次 memories 内的引用已统一为仓库级短编号，只能原样引用 allowedEvidenceIds。每个 module 必须包含 evidenceIds 数组；没有证据时显式输出 [] 并写明未知。每条 rule 必须有非空证据。不要省略空列表，不输出省略号或缩写结构。");
            require(canonical(context).length()<=230000,"全部记忆超出归纳预算，已保存各项记忆，请缩小单项记忆后重试");
            int formatFailures=0,validationFailures=0;
            for(int attempt=0;attempt<3;attempt++) {
                if(!store.update(id,owner,r->r.synthesisAttempts++))return;
                var response=llm.repositoryStage(run.request.model(),context);
                Synthesis synthesis;
                try {synthesis=parse(response,Synthesis.class);}
                catch(FlowFailure invalid) {
                    if(invalid.code!=FlowFailure.Code.MODEL_RESPONSE_INVALID)throw invalid;
                    if(!store.update(id,owner,r->r.previousFailure=invalid.getMessage()))return;
                    if(++formatFailures>1)throw invalid;
                    context.put("previousFailure",invalid.getMessage());
                    continue;
                }
                try {
                    validateReport(synthesis.report(),modelPacket(synthesisPacket));
                    synthesis=new Synthesis(synthesis.coveredTaskIds(),restoreReferences(synthesis.report(),synthesisPacket));
                    validateSynthesis(synthesis,run);
                }
                catch(IllegalArgumentException invalid) {
                    var detail=FlowFailure.validation(invalid);
                    if(!store.update(id,owner,r->r.previousFailure=detail.getMessage()))return;
                    if(++validationFailures>1)throw invalid;
                    context.put("previousFailure",detail.getMessage());
                    // Rejected drafts remain in memory only; every correction still receives all task memories.
                    // Keep the exact draft in the same short-ID namespace as this model context.
                    var draft=parse(response,Synthesis.class);
                    if(canonical(context).length()+canonical(draft).length()<230000)context.put("candidateToCorrect",draft);
                    continue;
                }
                var report=safeReport(synthesis.report());
                if(!store.update(id,owner,r->{r.report=report;r.summarizedTaskIds=List.copyOf(expected);r.model=response.model();r.failure=null;r.state="READY";}))return;
                break;
            }
        } catch(RuntimeException error) {
            var detail=failure(error instanceof IllegalArgumentException && !stage.equals("REPOSITORY_SOURCE") ? FlowFailure.validation(error) : error,stage);
            store.update(id,owner,r->{r.state="FAILED";r.failure=detail;if(r.currentTaskId!=null && r.tasks!=null)r.tasks.stream().filter(t->t.task.id().equals(r.currentTaskId) && t.memory==null).forEach(t->{t.state="FAILED";t.error=detail.message();});});
        }
    }

    /** Diagnostics contain schema coordinates and counts, never arbitrary rejected model values. */
    static List<String> toolIssues(Decision decision,RepositoryCatalogClient.Inventory inventory) {
        if(decision==null || decision.action()==null || !Set.of("LIST","READ","DONE").contains(decision.action()))return List.of("action：只允许 LIST、READ 或 DONE");
        var issues=new ArrayList<String>();
        if(decision.action().equals("READ")) {
            var files=decision.files();
            if(files==null || files.isEmpty())return List.of("READ.files：至少选择 1 个文件，实际为 "+(files==null?"null":"0 个"));
            if(files.size()>6)issues.add("READ.files：每次最多 6 个文件，实际为 "+files.size()+" 个");
            var known=new HashSet<>(inventory.files());var seen=new HashMap<String,Integer>();
            for(int i=0;i<files.size();i++) {
                String file=files.get(i),field="READ.files["+i+"]";
                if(file==null || file.isBlank())issues.add(field+"：路径不能为空");
                else {
                    if(!known.contains(file))issues.add(field+"：路径不在当前仓库提交的文件目录中，请先 LIST 并复制精确路径");
                    Integer previous=seen.putIfAbsent(file,i);
                    if(previous!=null)issues.add(field+"：与 files["+previous+"] 重复，请去重");
                }
                if(issues.size()>=12){issues.add("仅展示前 12 项问题；请按约束重新选择全部文件");break;}
            }
        } else if(decision.action().equals("LIST")) {
            String p=Objects.toString(decision.prefix(),""),q=Objects.toString(decision.query(),"");
            if(p.startsWith("/") || p.contains("..") || p.length()>1000)issues.add("LIST.prefix：应为仓库内相对目录前缀，不能包含 ..，最多 1000 字符");
            if(q.length()>200)issues.add("LIST.query：最多 200 字符，实际为 "+q.length()+" 字符");
            if(decision.offset()!=null && (decision.offset()<0 || decision.offset()>30000))issues.add("LIST.offset：应在 0 至 30000 之间，实际为 "+decision.offset());
        }
        return List.copyOf(issues);
    }

    static void validatePlan(Plan plan,RepositoryCatalogClient.Inventory index) {
        reportRequire(plan!=null,"plan：缺少完整分析计划");
        reportRequire(plan.summary()!=null && !plan.summary().isBlank() && plan.summary().length()<=2000,"summary：需要非空分析思路，最多 2000 字符");
        reportRequire(plan.tasks()!=null && !plan.tasks().isEmpty() && plan.tasks().size()<=32,"tasks：需要 1 至 32 个分析项");
        var known=index.modules().stream().map(RepositoryCatalogClient.Module::modulePath).collect(java.util.stream.Collectors.toSet());
        var covered=new HashSet<String>();var ids=new HashSet<String>();
        for(int i=0;i<plan.tasks().size();i++) {
            var task=plan.tasks().get(i);String field="tasks["+i+"]";
            reportRequire(task!=null,field+"：分析项不能为空");
            reportRequire(task.configurationObjective()!=null && !task.configurationObjective().isBlank() && task.configurationObjective().length()<=2000,field+".configurationObjective：每个分析项都需要配置管理取证目标，最多 2000 字符；无配置证据时也需规划如何确认缺口");
            reportRequire(task.id()!=null && task.id().matches("[A-Za-z][A-Za-z0-9_-]{0,59}") && !ids.contains(task.id()),field+".id：需要字母开头且唯一的任务标识，最多 60 字符");
            reportRequire(task.dependsOn()!=null && ids.containsAll(task.dependsOn()),field+".dependsOn：只能引用前面已声明的任务 ID，不能引用当前或后续任务");
            reportRequire(task.title()!=null && !task.title().isBlank() && task.title().length()<=200,field+".title：需要非空标题，最多 200 字符");
            reportRequire(task.objective()!=null && !task.objective().isBlank() && task.objective().length()<=2000,field+".objective：需要非空分析目标，最多 2000 字符");
            reportRequire(task.modulePaths()!=null && !task.modulePaths().isEmpty(),field+".modulePaths：至少分配一个 allowedModulePaths 中的模块");
            for(int j=0;j<task.modulePaths().size();j++) {
                String module=task.modulePaths().get(j),path=field+".modulePaths["+j+"]";
                reportRequire(known.contains(module),path+"：分析项模块超出仓库范围，请从 allowedModulePaths 的 "+known.size()+" 个标识原样选择；目录名不能替代模块标识");
                reportRequire(covered.add(module),path+"：模块重复分配，每个 allowedModulePaths 标识必须恰好出现一次");
            }
            ids.add(task.id());
        }
        reportRequire(covered.equals(known),"tasks.modulePaths：分析计划遗漏 "+(known.size()-covered.size())+" 个模块，必须恰好覆盖 allowedModulePaths 的全部 "+known.size()+" 个标识");
    }
    static void validateSynthesis(Synthesis synthesis,Run run) {
        var expected=run.tasks.stream().map(t->t.task.id()).toList();
        reportRequire(synthesis!=null && synthesis.coveredTaskIds()!=null && synthesis.coveredTaskIds().size()==expected.size() && new HashSet<>(synthesis.coveredTaskIds()).equals(new HashSet<>(expected)),"coveredTaskIds 必须包含全部 "+expected.size()+" 项记忆，不能遗漏或重复");
        validateReport(synthesis.report(),packet(run.inventory,allSources(run)));
        var modules=run.inventory.modules().stream().map(RepositoryCatalogClient.Module::modulePath).collect(java.util.stream.Collectors.toSet());
        reportRequire(modulePaths(synthesis.report()).equals(modules) && synthesis.report().modules().size()==modules.size(),"report.modules 必须恰好覆盖全部 "+modules.size()+" 个模块，实际为 "+synthesis.report().modules().size()+" 项，存在遗漏或重复");
    }
    private static Set<String> modulePaths(Report report){return report.modules().stream().map(ModuleRole::modulePath).collect(java.util.stream.Collectors.toSet());}
    static <T> T parse(FlowModelClient.Response response,Class<T> type){return RepositoryModelJson.parse(response,type);}
    static RepositoryCatalogClient.BackgroundPacket packet(RepositoryCatalogClient.Inventory index,List<RepositoryCatalogClient.SourceEvidence> sources){
        var warnings=new ArrayList<>(index.warnings());
        var unread=index.modules().stream().filter(m->sources.stream().noneMatch(s->!s.kind().equals("UNSUPPORTED") && !s.text().isBlank() && s.projectId()==m.projectId())).map(RepositoryCatalogClient.Module::modulePath).toList();
        if(!unread.isEmpty())warnings.add("以下模块尚无本模块可用文件证据，职责与运行约束仍需确认："+String.join("、",unread));
        long unsupported=sources.stream().filter(s->s.kind().equals("UNSUPPORTED")).count(),truncated=sources.stream().filter(RepositoryCatalogClient.SourceEvidence::truncated).count();
        if(unsupported>0)warnings.add(unsupported+" 个已选择文件不受安全读取器支持，不能视为已完成内容分析");
        if(truncated>0)warnings.add(truncated+" 份源码已截断，结论只覆盖提供的片段");
        return new RepositoryCatalogClient.BackgroundPacket(index.repositoryKey(),index.name(),index.branch(),index.commitId(),index.environment(),index.modules(),sources,List.copyOf(warnings));
    }
    static List<RepositoryCatalogClient.SourceEvidence> allSources(Run run){var sources=new LinkedHashMap<String,RepositoryCatalogClient.SourceEvidence>();run.tasks.forEach(t->t.sources.forEach(s->sources.putIfAbsent(s.id(),s)));return List.copyOf(sources.values());}
    static Map<String,Object> overview(RepositoryCatalogClient.Inventory index) {
        var dirs=new TreeMap<String,Integer>();
        for(String file:index.files()){String[] parts=file.split("/");String directory=parts.length==1?".":String.join("/",Arrays.copyOf(parts,Math.min(3,parts.length-1)));dirs.merge(directory,1,Integer::sum);}
        var result=new LinkedHashMap<String,Object>();result.put("repositoryKey",index.repositoryKey());result.put("name",index.name());result.put("branch",index.branch());result.put("commitId",index.commitId());result.put("modules",index.modules());result.put("warnings",index.warnings());
        result.put("directories",dirs.entrySet().stream().limit(500).map(e->Map.of("path",e.getKey(),"fileCount",e.getValue())).toList());result.put("directoryTruncated",dirs.size()>500);result.put("totalFiles",index.files().size());
        var configFiles=index.files().stream().filter(f->f.matches("(?i).*\\.(?:ya?ml|properties|json)$")).toList();
        result.put("configurationFiles",configFiles.stream().limit(250).toList());result.put("configurationFileCount",configFiles.size());result.put("configurationFilesTruncated",configFiles.size()>250);
        result.put("requiredTopics",List.of("配置文件与配置中心接入","父模块共享配置与依赖","profile 激活、导入与覆盖优先级","属性绑定、默认值来源与动态刷新","配置对鉴权、路由、数据库和中间件行为的影响","仓库声明与部署实际值的证据边界"));
        result.put("analysisLimits",Map.of("maxTasks",32,"maxRoundsPerTask",12,"maxFilesPerTask",24,"maxSourceCharactersPerTask",96000,"maxMemoryCharactersPerTask",4500));return result;
    }
    static Map<String,Object> filePage(RepositoryCatalogClient.Inventory index,Task task,String prefix,String query,int offset,boolean own) {
        String p=prefix==null?"":prefix,q=query==null?"":query;
        require(!p.startsWith("/") && !p.contains("..") && p.length()<=1000 && q.length()<=200 && offset>=0 && offset<=30000,"文件目录查询越界");
        var files=index.files().stream().filter(f->f.startsWith(p) && f.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
                .filter(f->!own || !f.contains("/") || task.modulePaths().stream().anyMatch(m->m.equals(".") || f.startsWith(m+"/"))).sorted().toList();
        return Map.of("files",files.stream().skip(offset).limit(150).toList(),"total",files.size(),"offset",offset,"hasMore",offset+150<files.size());
    }
    private static String effectiveState(Run run,TaskRun task) {
        return task.memory==null && task.state.equals("ANALYZING") && Set.of("FAILED","CANCELLED").contains(run.state)?run.state:task.state;
    }
    static Progress progress(Run run){return run.plan==null?null:new Progress(run.plan.summary(),run.tasks.stream().map(t->new TaskView(t.task,effectiveState(run,t),t.rounds,t.attempts,t.model,t.error,t.memory,t.sources.size(),t.activity,t.pendingFiles==null?List.of():t.pendingFiles,t.sources.stream().map(RepositoryCatalogClient.SourceEvidence::filePath).toList(),t.toolHistory==null?List.of():List.copyOf(t.toolHistory),t.toolFeedback)).toList(),run.currentTaskId,run.summarizedTaskIds==null?List.of():run.summarizedTaskIds,run.synthesisAttempts);}
}
