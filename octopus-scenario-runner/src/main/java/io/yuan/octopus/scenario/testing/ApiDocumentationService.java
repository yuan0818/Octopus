package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Documentation is generated from the same pinned contracts consumed by planning. */
@Service
public class ApiDocumentationService {
    private final BusinessKnowledgeStore store;
    public ApiDocumentationService(BusinessKnowledgeStore store) {this.store=store;}
    public record ExportRequest(String title,List<String> operationRefs) {}
    public record Document(String digest,Map<String,Object> openApi,List<String> warnings) {}
    public Document export(ExportRequest request) {
        require(request.operationRefs()!=null && !request.operationRefs().isEmpty() && request.operationRefs().size()<=1000,"请选择 1–1000 个 API 版本");
        return exportContracts(request.title(),request.operationRefs().stream().distinct().map(store::operation).toList());
    }
    static Document exportContracts(String title,List<Operation> operations) {
        Map<String,Object> paths=new TreeMap<>();List<String> warnings=new ArrayList<>();
        Set<String> services=new HashSet<>();operations.forEach(o->services.add(o.system()+"."+o.service()));
        require(services.size()==1,"OpenAPI 导出需选择同一个服务，避免同路径接口混淆");
        for(var operation:operations) {
            require(operation.requestSchema()!=null && operation.responseSchema()!=null,"API 契约不完整，不能发布："+operation.ref());
            @SuppressWarnings("unchecked") var path=(Map<String,Object>)paths.computeIfAbsent(operation.path(),k->new TreeMap<>());
            require(!path.containsKey(operation.method().toLowerCase(Locale.ROOT)),"同路径方法存在多个版本，请明确选择："+operation.path());
            Map<String,Object> item=new LinkedHashMap<>();
            String prefix=operation.system()+"."+operation.service()+".";
            item.put("operationId",operation.operationId().startsWith(prefix)?operation.operationId().substring(prefix.length()):operation.operationId());
            item.put("x-octopus-revision",operation.revision());item.put("x-octopus-source",operation.source());
            item.put("x-octopus-implementations",operation.implementations());
            List<Object> parameters=new ArrayList<>();
            var schemas=operation.requestSchema().get("x-octopus-parameters") instanceof Map<?,?> map?map:Map.of();
            Map<String,Object> bodyProperties=new TreeMap<>();List<String> required=new ArrayList<>();
            for(var entry:operation.inputs().entrySet()) {
                var port=entry.getValue();
                if("body".equals(port.location())) {
                    if(!"$".equals(port.name())) {
                        var properties=operation.requestSchema().get("properties") instanceof Map<?,?> p?p:Map.of();
                        bodyProperties.put(port.name(),properties.containsKey(port.name())?properties.get(port.name()):Map.of("type",port.type()));
                        if(port.required()) required.add(port.name());
                    }
                    continue;
                }
                Map<String,Object> parameter=new LinkedHashMap<>();parameter.put("name",port.name());parameter.put("in",port.location());parameter.put("required",port.required());
                parameter.put("schema",schemas.containsKey(entry.getKey())?schemas.get(entry.getKey()):Map.of("type",port.type()));
                if(port.prefix()!=null) parameter.put("x-octopus-prefix",port.prefix());
                parameters.add(parameter);
            }
            if(!parameters.isEmpty()) item.put("parameters",parameters);
            var bodyPorts=operation.inputs().values().stream().filter(p->"body".equals(p.location())).toList();
            if(!bodyPorts.isEmpty()) {
                var schema=new LinkedHashMap<>(operation.requestSchema());schema.remove("x-octopus-parameters");
                if(bodyPorts.stream().noneMatch(p->"$".equals(p.name()))) {
                    schema.put("type","object");schema.put("properties",bodyProperties);schema.put("required",required);
                }
                item.put("requestBody",Map.of("required",bodyPorts.stream().anyMatch(Port::required),"content",Map.of("application/json",Map.of("schema",schema))));
            }
            Map<String,Object> responses=new TreeMap<>();
            operation.responseSchema().forEach((status,schema)->{
                require(status.matches("[1-5][0-9]{2}|default"),"响应必须明确状态码或 default");
                boolean noBody=schema instanceof Map<?,?> map && "null".equals(map.get("type"));
                responses.put(status,noBody?Map.of("description","No response body"):Map.of("description","Contract response","content",Map.of("application/json",Map.of("schema",schema))));
            });
            require(!responses.isEmpty(),"API 缺少响应契约："+operation.ref());item.put("responses",responses);
            if(operation.securityProfile()!=null) warnings.add(operation.ref()+" 的运行时认证扩展需单独配置，不包含凭据");
            path.put(operation.method().toLowerCase(Locale.ROOT),item);
        }
        Map<String,Object> document=Map.of("openapi","3.1.0","info",Map.of("title",title==null||title.isBlank()?"API contracts":title,"version","snapshot"),
                "paths",paths,"x-octopus-capability-scope","JSON bodies; scalar path/query/header parameters; explicit status responses");
        return new Document(hash(document),document,warnings);
    }
    public record Difference(String path,Object before,Object after) {}
    public List<Difference> compare(String before,String after) {
        List<Difference> result=new ArrayList<>();
        diff("",GSON.toJsonTree(store.operation(before)),GSON.toJsonTree(store.operation(after)),result);
        return result;
    }
    private static void diff(String path,com.google.gson.JsonElement before,com.google.gson.JsonElement after,List<Difference> result) {
        if(Objects.equals(before,after)) return;
        if(before!=null && after!=null && before.isJsonObject() && after.isJsonObject()) {
            Set<String> keys=new TreeSet<>(before.getAsJsonObject().keySet());keys.addAll(after.getAsJsonObject().keySet());
            for(String key:keys) diff(path+"/"+key,before.getAsJsonObject().get(key),after.getAsJsonObject().get(key),result);
        } else result.add(new Difference(path,before==null?null:GSON.fromJson(before,Object.class),after==null?null:GSON.fromJson(after,Object.class)));
    }
}
