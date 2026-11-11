package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
public class OrderedFlowPlanningService {
    public record Request(String goal,List<String> operationRefs,String model) {}
    public record PlannedStep(String id,String label,String mode,List<String> operationRefs,String reason) {}
    public record Plan(String name,List<PlannedStep> steps,List<String> assumptions,List<String> questions) {}
    public record Candidate(Flow flow,Plan plan,String model,List<String> searchedOperationRefs,String catalogHash) {}
    private final BusinessKnowledgeStore store;
    private final FlowModelClient client;
    public OrderedFlowPlanningService(BusinessKnowledgeStore store,FlowModelClient client) {this.store=store;this.client=client;}
    public Candidate generate(Request request) {
        require(request.goal()!=null && !request.goal().isBlank() && request.goal().length()<=12000,"请填写业务目标，最多 12000 字");
        require(request.operationRefs()!=null && !request.operationRefs().isEmpty() && request.operationRefs().size()<=300,"请选择 1–300 个候选 API 版本");
        var operations=request.operationRefs().stream().distinct().map(store::operation).toList();
        var byRef=new LinkedHashMap<String,Operation>();operations.forEach(o->byRef.put(o.ref(),o));
        var facts=operations.stream().map(o->Map.<String,Object>of("ref",o.ref(),"method",o.method(),"path",o.path(),
                "inputs",o.inputs(),"requestSchema",o.requestSchema()==null?Map.of():o.requestSchema(),
                "responseSchema",o.responseSchema()==null?Map.of():o.responseSchema(),"source",o.source())).toList();
        Map<String,Object> context=new LinkedHashMap<>();context.put("goal",request.goal());context.put("operations",facts);
        int formatRepairs=0,semanticRepairs=0;
        while(true) {
            var response=client.planOrdered(request.model(),context);
            Plan plan;
            try {
                require(response.content()!=null && response.content().length()<=200000,"Plan response exceeds limit");
                plan=read(response.content().strip().replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$",""),Plan.class);
            } catch(RuntimeException e) {
                if(formatRepairs++>=2) throw new IllegalArgumentException("流程规划格式修复次数已用完，原草稿未改变");
                context.put("previousFailure","输出必须符合指定 Plan JSON 结构，不得添加字段");continue;
            }
            try { return materialize(plan,response.model(),byRef); }
            catch(IllegalArgumentException e) {
                if(semanticRepairs++>=1) throw e;
                context.put("previousFailure",e.getMessage());
            }
        }
    }
    static Candidate materialize(Plan plan,String model,Map<String,Operation> operations) {
        require(plan!=null && plan.name()!=null && !plan.name().isBlank() && plan.name().length()<=300
                && plan.steps()!=null && plan.steps().size()<=100 && plan.assumptions()!=null && plan.questions()!=null,"流程规划结构不完整");
        for(var list:List.of(plan.assumptions(),plan.questions())) require(list.size()<=100 && list.stream().allMatch(s->s!=null && s.length()<=4000),"规划说明超过限制");
        List<FlowStep> groups=new ArrayList<>();Map<String,List<Assertion>> assertions=new LinkedHashMap<>();
        List<String> ids=new ArrayList<>();Set<String> stepIds=new HashSet<>();int count=0;
        for(var step:plan.steps()) {
            identifier(step.id(),"step id");require(stepIds.add(step.id()),"重复的步骤 ID");
            require(step.label()!=null && step.reason()!=null && !step.reason().isBlank() && step.reason().length()<=4000,"步骤缺少选择和排序依据");
            require(Set.of("SERIAL","PARALLEL").contains(step.mode()) && step.operationRefs()!=null && !step.operationRefs().isEmpty(),"步骤只能包含串行或并行调用列表");
            List<FlowNode> calls=new ArrayList<>();
            for(String ref:step.operationRefs()) {
                require(++count<=300,"流程最多 300 次 API 调用");var operation=operations.get(ref);
                require(operation!=null,"规划选择了候选范围之外的 API");
                require(!"PARALLEL".equals(step.mode()) || Set.of("GET","HEAD","OPTIONS").contains(operation.method()),"自动规划只允许独立只读查询并行，写调用须串行");
                String id=step.id()+"_call_"+calls.size();ids.add(id);
                calls.add(new FlowNode(id,operation.method()+" "+operation.path(),ref,Map.of(),List.of(),List.of(),0));
                // Contract statuses are candidates; never invent a success status for missing contracts.
                String status=operation.responseSchema()==null?null:operation.responseSchema().keySet().stream().filter(k->k.matches("2[0-9]{2}")).sorted().findFirst().orElse(null);
                assertions.put(id,status==null?List.of():List.of(new Assertion("status","STATUS_EQUALS",null,Integer.parseInt(status))));
            }
            groups.add(new FlowStep(step.id(),step.label(),step.mode(),calls));
        }
        require(!groups.isEmpty() || !plan.questions().isEmpty(),"没有可用流程时必须给出具体缺口");
        Flow flow=new Flow("flow_"+UUID.randomUUID().toString().replace("-",""),0,plan.name(),Map.of(),null,List.of(),List.of(),
                List.of(new TestPoint("normal","正常业务流程",ids,assertions,Map.of(),null)),groups);
        return new Candidate(flow,plan,model,List.copyOf(operations.keySet()),hash(operations));
    }
}
