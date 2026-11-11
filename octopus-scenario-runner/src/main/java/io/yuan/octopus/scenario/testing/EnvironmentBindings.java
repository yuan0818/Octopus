package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.IrValidator.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Environment inputs are resolved per consuming call, separately from runtime output context. */
@Service
public class EnvironmentBindings {
    private final ProjectContextService contexts;
    private final RepositoryCatalogClient catalog;
    public EnvironmentBindings(ProjectContextService contexts,RepositoryCatalogClient catalog) {
        this.contexts=contexts;this.catalog=catalog;
    }
    static EnvironmentReference reference(Object value) {
        var ref=value instanceof String key ? new EnvironmentReference(key,null,null) : read(value,EnvironmentReference.class);
        require(ref!=null,"Environment reference required"); identifier(ref.key(),"environment key");
        require(ref.repositoryKey()!=null || ref.modulePath()==null,"Cross-module reference requires repositoryKey");
        return ref;
    }
    public ExecutionProfile resolve(TestIR ir,ExecutionProfile requested) {
        require(requested!=null,"Execution profile required");
        Map<String,CallEnvironment> calls=new LinkedHashMap<>();
        Map<String,Operation> operations=new HashMap<>();ir.operations().forEach(o->operations.put(o.ref(),o));
        List<RepositoryCatalogClient.Scope> scopes=null;
        var allCalls=new ArrayList<>(ir.steps());
        if(ir.cleanup()!=null) ir.cleanup().forEach(c->allCalls.add(c.call()));
        for(var call:allCalls) {
            var bindings=call.inputs().entrySet().stream().filter(e->"environment".equals(e.getValue().source())).toList();
            if(bindings.isEmpty()) continue;
            ProjectContextService.environment(requested.environment());
            if(scopes==null) scopes=catalog.scopes();
            Map<String,Object> values=new TreeMap<>();Map<String,String> origins=new TreeMap<>();
            for(var binding:bindings) {
                var ref=reference(binding.getValue().value());
                String repository=ref.repositoryKey(), module=ref.modulePath()==null?"":ref.modulePath();
                if(repository==null) {
                    var operation=operations.get(call.operationRef());
                    Set<List<String>> matches=new LinkedHashSet<>();
                    for(var impl:operation.implementations()) for(var scope:scopes) for(var candidate:scope.modules())
                        if(candidate.projectId()==impl.projectId()) matches.add(List.of(scope.key(),candidate.modulePath()));
                    require(matches.size()==1,"Call "+call.id()+" requires an explicit environment source scope");
                    var source=matches.iterator().next();repository=source.get(0);module=source.get(1);
                }
                var resolved=contexts.get(repository,module,requested.environment());
                var variable=resolved.effective().variables().stream().filter(v->v.key().equals(ref.key())).findFirst()
                        .orElseThrow(()->new IllegalArgumentException("Missing environment variable for "+call.id()+": "+ref.key()));
                var port=operations.get(call.operationRef()).inputs().get(binding.getKey());
                require(port!=null && matches(variable.value(),port.type()),"Environment input type mismatch: "+call.id()+"."+binding.getKey());
                values.put(binding.getKey(),variable.value());
                origins.put(binding.getKey(),repository+"/"+module+"@"+requested.environment()+":"+ref.key()+"/"+resolved.variableOrigins().get(ref.key()));
            }
            calls.put(call.id(),new CallEnvironment(values,origins,hash(Map.of("values",values,"origins",origins))));
        }
        // Ignore client-supplied resolutions: only server-resolved snapshots can reach execution.
        return new ExecutionProfile(requested.services(),requested.initialContext(),requested.secretEnvironmentNames(),requested.environment(),calls);
    }
}
