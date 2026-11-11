package io.yuan.octopus.scenario.testing;

import org.springframework.web.bind.annotation.*;
import java.util.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;

/** Read-only conversation. Only the explicit analysis endpoint can schedule durable work. */
@RestController
@RequestMapping("/api/business-testing/repository-analysis/chat")
public class RepositoryKnowledgeChat {
    public record Message(String role,String content) {}
    public record Request(RepositoryAnalysisService.Request scope,String modulePath,String question,List<Message> history,String backgroundTaskId) {
        public Request(RepositoryAnalysisService.Request scope,String modulePath,String question,List<Message> history){this(scope,modulePath,question,history,null);}
    }
    public record Answer(String answer,List<String> evidenceIds) {}
    public record Reply(String answer,List<String> evidenceIds,String model,String backgroundUpdatedAt) {}
    private final RepositoryAnalysisService analysis;
    private final ProjectContextService background;
    private final RepositoryCatalogClient catalog;
    private final FlowModelClient llm;
    public RepositoryKnowledgeChat(RepositoryAnalysisService analysis,ProjectContextService background,RepositoryCatalogClient catalog,FlowModelClient llm) {
        this.analysis=analysis;this.background=background;this.catalog=catalog;this.llm=llm;
    }
    @PostMapping public Reply chat(@RequestBody Request request) {
        require(request!=null && request.scope()!=null,"请选择背景范围");
        require(request.question()!=null && !request.question().isBlank() && request.question().length()<=6000,"问题应为 1–6000 字符");
        var history=request.history()==null?List.<Message>of():request.history();
        require(history.size()<=12 && history.stream().allMatch(m->m!=null && Set.of("user","assistant").contains(Objects.toString(m.role(),"")) && m.content()!=null && m.content().length()<=6000),"对话记录超出范围");
        var scope=request.scope();String module=Objects.toString(request.modulePath(),"");
        catalog.validate(scope.repositoryKey(),module);
        var adopted=analysis.adopted(scope);
        var task=adopted==null?null:adopted.analysis();
        if(task==null)throw new FlowFailure(FlowFailure.Code.CONTEXT_UNAVAILABLE,null,"此版本尚无已采用背景，请先采用已完成的分析结论");
        require(request.backgroundTaskId()==null || Objects.equals(request.backgroundTaskId(),task.taskId()),"已采用背景已更新，请刷新后重新提问");
        var context=new LinkedHashMap<String,Object>();
        context.put("scope",Map.of("repositoryKey",scope.repositoryKey(),"branch",scope.branch(),"commitId",scope.commitId()));
        context.put("focusModule",module);context.put("question",request.question());context.put("history",history);
        context.put("humanBackground",List.of()); // Runtime environment context belongs to flow preparation, not versioned knowledge.
        boolean ready=task!=null && "READY".equals(task.state());
        context.put("report",ready?task.report():null);
        context.put("analysisState",task==null?"NOT_ANALYZED":task.state());
        context.put("warnings",task==null?List.of():task.warnings());
        // Keep repository-wide rules, including gateways outside the selected module. No raw source or credentials.
        var evidence=ready?task.sources():List.<RepositoryCatalogClient.SourceEvidence>of();
        context.put("evidence",evidence.stream().map(s->Map.of("id",s.id(),"filePath",s.filePath(),"lineStart",s.lineStart(),"lineEnd",s.lineEnd())).toList());
        var response=llm.repositoryChat(scope.model(),context);
        try {
            var answer=read(response.content(),Answer.class);
            require(answer!=null && answer.answer()!=null && !answer.answer().isBlank() && answer.answer().length()<=6000 && answer.evidenceIds()!=null && answer.evidenceIds().size()<=30,"回答格式无效");
            require(evidence.stream().map(RepositoryCatalogClient.SourceEvidence::id).toList().containsAll(answer.evidenceIds()),"回答引用未知证据");
            return new Reply(answer.answer(),answer.evidenceIds().stream().distinct().toList(),response.model(),ready?task.updatedAt():null);
        } catch(RuntimeException error) { throw new FlowFailure(FlowFailure.Code.MODEL_RESPONSE_INVALID); }
    }
}
