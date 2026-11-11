package io.yuan.octopus.scenario.testing;

import io.yuan.octopus.preparation.*;
import java.util.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.IrValidator.*;

final class PreparationChecks {
    static boolean supplies(io.yuan.octopus.preparation.PreparationPlan plan,Port port) {
        return plan!=null && ("body".equals(port.location()) && plan.body()!=null || "header".equals(port.location()) && plan.headers().keySet().stream().anyMatch(h -> h.equalsIgnoreCase(port.name())));
    }
    static void validate(PreparationPlan plan,Operation operation,Map<String,Binding> inputs,
                         Map<String,Variable> variables,Map<String,String> producers,Set<String> dependencies) {
        validate(plan,operation,inputs,variables,producers,dependencies,variables);
    }
    static void validate(PreparationPlan plan,Operation operation,Map<String,Binding> inputs,
                         Map<String,Variable> variables,Map<String,String> producers,Set<String> dependencies,
                         Map<String,Variable> availableContext) {
        if(plan==null) return;
        PreparationTools.validate(plan);
        var results=new HashMap<String,String>();
        for(int i=0;i<plan.tools().size();i++) {
            var call=plan.tools().get(i); var tool=PreparationTools.get(call.tool(),call.version());
            String path="preparation.tools["+i+"].inputs";
            call.inputs().forEach((key,value) -> binding(value,tool.inputs().getOrDefault(key,"any"),operation,inputs,variables,producers,dependencies,results,
                    path+(tool.inputs().containsKey(key)?"."+key:"[*]"),availableContext));
            for(String key:tool.sensitiveInputs()) {
                var v=call.inputs().get(key);
                require(Set.of("secret","context").contains(v.source()),"密钥必须引用运行时凭据或敏感上下文");
                if(v.source().equals("context")) require(variables.get(String.valueOf(v.value())).sensitive(),"密钥上下文必须标记敏感");
            }
            if(call.inputs().containsKey("algorithm")) {
                var a=call.inputs().get("algorithm");
                require("literal".equals(a.source()) && Set.of("MD5","SHA-256").contains(String.valueOf(a.value())),"请明确选择 MD5 或 SHA-256 算法");
            }
            if(call.tool().equals("platform.sign")) {
                var a=call.inputs();
                require(a.get("query").equals(new PreparationPlan.Value("request","query")),"Platform 签名必须使用实际请求 Query");
                require(Objects.equals(a.get("body"),plan.body()==null?new PreparationPlan.Value("request","body"):plan.body()),"Platform 签名必须使用最终请求体");
                for(String h:List.of("appid","requestid","timestamp"))
                    require(Objects.equals(plan.headers().get(h),a.get(h)),"Platform 签名和实际请求头必须使用同一次计算结果");
                require(Objects.equals(plan.headers().get("sign"),new PreparationPlan.Value("result",call.id()+".sign")),"请将签名结果绑定至 sign 请求头");
            }
            tool.outputs().forEach((key,type)->results.put(call.id()+"."+key,type));
        }
        if(plan.body()!=null) binding(plan.body(),"object",operation,inputs,variables,producers,dependencies,results,"preparation.body",availableContext);
        plan.headers().values().forEach(v -> binding(v,"string",operation,inputs,variables,producers,dependencies,results,"preparation.headers[*]",availableContext));
    }
    private static void binding(PreparationPlan.Value v,String expected,Operation operation,Map<String,Binding> inputs,
                                Map<String,Variable> variables,Map<String,String> producers,Set<String> deps,Map<String,String> results,String path,Map<String,Variable> availableContext) {
        String key=String.valueOf(v.value()), type;
        switch(v.source()) {
            case "literal" -> { require(expected.equals("any") || matches(v.value(),expected),"预处理参数类型错误"); return; }
            case "secret" -> type="string";
            case "context" -> {
                var variable=variables.get(key);
                if(variable==null)throw new FlowBindingException("CONTEXT_UNDECLARED",path,expected,null,null,FlowBindingException.matching(availableContext,expected));
                if(!availableContext.containsKey(key))throw new FlowBindingException("CONTEXT_NOT_UPSTREAM",path,expected,variable.type(),null,FlowBindingException.matching(availableContext,expected));
                type=variable.type();
                if(!FlowBindingException.compatible(type,expected))throw new FlowBindingException("CONTEXT_TYPE_MISMATCH",path,expected,type,key,FlowBindingException.matching(availableContext,expected));
                if(producers.containsKey(key)) deps.add(producers.get(key));
            }
            case "input" -> { var port=operation.inputs().get(key); require(port!=null && inputs.containsKey(key),"预处理引用的 API 输入尚未填写: "+key); type=port.type(); }
            case "result" -> { type=results.get(key); require(type!=null,"预处理只能引用前面动作的输出: "+key); }
            case "request" -> { require(Set.of("body","query","headers").contains(key),"未知请求数据引用"); type="object"; }
            default -> throw new IllegalArgumentException("未知预处理绑定来源");
        }
        require(expected.equals("any") || type.equals(expected) || type.equals("integer") && expected.equals("number"),"预处理绑定类型不匹配: "+key);
    }
}
