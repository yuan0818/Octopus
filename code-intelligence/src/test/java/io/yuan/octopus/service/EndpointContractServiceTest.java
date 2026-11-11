package io.yuan.octopus.service;
import io.yuan.octopus.entity.*;
import io.yuan.octopus.repository.*;
import io.yuan.octopus.dto.response.EndpointDTO;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EndpointContractServiceTest {
    static final String COMMIT = "a".repeat(40);
    final ClassInfoRepository classes = mock(ClassInfoRepository.class);
    final MethodInfoRepository methods = mock(MethodInfoRepository.class);
    final AnalyzedProjectRepository projects = mock(AnalyzedProjectRepository.class);
    final GitMethodSourceClient source = mock(GitMethodSourceClient.class);
    final QueryService query = mock(QueryService.class);
    final EndpointContractService service = new EndpointContractService(classes, methods, projects, source, query);
    final List<ClassInfo> snapshot = new ArrayList<>();
    final Map<String,String> files = new HashMap<>();
    ClassInfo add(String name, String text) {
        var c = ClassInfo.builder().id((long)snapshot.size()+10).projectId(1L).branch("feature").commitId(COMMIT)
                .qualifiedName(name).sourceFilePath(name+".java").build(); snapshot.add(c); files.put(c.getSourceFilePath(),text); return c;
    }
    void sources() {
        when(source.fetchBatch(eq(1L),eq(COMMIT),anyList())).thenAnswer(i -> ((List<GitMethodSourceClient.SourceItem>)i.getArgument(2))
                .stream().map(item -> files.get(item.filePath())).toList());
    }
    @Test void extractsBindingsInheritedGenericDtosAndSerializationNamesFromExactSource() {
        var c = add("app.Api", """
                package app;
                class Api {
                  @PostMapping("/items/{id}") @ResponseStatus(HttpStatus.CREATED)
                  Result<Item> create(@PathVariable("id") long id, @RequestHeader(name="X-Trace",required=false) String trace,
                    @RequestParam(defaultValue="1") int page, @RequestBody Request req, HttpServletRequest servlet) { return null; }
                }
                """);
        add("app.Request", "package app; class Request extends Envelope<java.util.List<Item>> { @JsonProperty(\"display_name\") @NotBlank String name; @JsonIgnore String hidden; static int constant; }");
        add("app.Envelope", "package app; class Envelope<T> { T content; }");
        add("app.Item", "package app; class Item { String id; @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String password; }");
        add("app.Result", "package app; class Result<T> { int code; T data; }");
        sources();
        var m = MethodInfo.builder().methodName("create").methodSignature("app.Api.create(long,String,int,app.Request,HttpServletRequest):app.Result<app.Item>")
                .parameterTypes("[\"long\",\"String\",\"int\",\"app.Request\",\"HttpServletRequest\"]").build();
        var result = service.new Reader(snapshot,COMMIT).read(c,m,"POST","/items/{id}","feature");
        assertEquals("integer",result.inputs().get("path.id").type());
        assertFalse(result.inputs().get("query.page").required());
        assertFalse(result.inputs().get("header.X-Trace").required());
        assertTrue(result.inputs().get("body.display_name").required());
        assertEquals("array",result.inputs().get("body.content").type());
        assertFalse(result.inputs().containsKey("body.hidden")); assertFalse(result.inputs().containsKey("body.constant"));
        var body = (Map<?,?>)result.responseSchema().get("201"); var props = (Map<?,?>)body.get("properties");
        var data = (Map<?,?>)((Map<?,?>)props.get("data")).get("properties");
        assertEquals("string",((Map<?,?>)data.get("id")).get("type")); assertFalse(data.containsKey("password"));
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        verify(source,atLeastOnce()).fetchBatch(eq(1L),eq(COMMIT),anyList());
    }
    @Test void resolvesOnlyTheMatchingDirectDelegatedOverloadAndKeepsUnknownTypesExplicit() {
        var c=add("app.Api", "package app; class Api { Service service; Result login(@RequestBody Req req) { if(req==null) return new Result(400); return service.login(req); } }");
        add("app.Service", "package app; class Service { Result<Login> login(Req req){return null;} Result<Other> login(String value){return null;} }");
        add("app.Req", "package app; class Req { Unknown value; }");
        var login=add("app.Login", "package app; class Login { String token; }");
        snapshot.add(ClassInfo.builder().id(99L).projectId(2L).branch("feature").commitId(COMMIT)
                .qualifiedName(login.getQualifiedName()).sourceFilePath(login.getSourceFilePath()).build());
        add("app.Result", "package app; class Result<T> { T data; }"); sources();
        var result=service.new Reader(snapshot,COMMIT).read(c,MethodInfo.builder().methodName("login").methodSignature("sig")
                .parameterTypes("[\"app.Req\"]").build(),"POST","/login","feature");
        var schema=(Map<?,?>)result.responseSchema().get("200");
        assertTrue(schema.toString().contains("token={type=string}"));
        assertFalse(result.inputs().containsKey("body.value"));
        assertTrue(result.warnings().stream().anyMatch(w->w.contains("Unknown")));
        assertTrue(result.warnings().stream().anyMatch(w->w.contains("其他分支")));
    }
    @Test void multipleReturnPathsDoNotBorrowTheContractOfOnlyOneBranch() {
        var c=add("app.Api", "package app; class Api { Service service; Result login(@RequestBody Req req) { if(req==null) return unknown(); return service.login(req); } }");
        add("app.Service", "package app; class Service { Result<Login> login(Req req){return null;} }");
        add("app.Req", "package app; class Req { String name; }");
        add("app.Login", "package app; class Login { String token; }");
        add("app.Result", "package app; class Result<T> { T data; }"); sources();
        var result=service.new Reader(snapshot,COMMIT).read(c,MethodInfo.builder().methodName("login").methodSignature("sig")
                .parameterTypes("[\"app.Req\"]").build(),"POST","/login","feature");
        assertFalse(result.responseSchema().toString().contains("token"));
        assertTrue(result.responseSchema().toString().contains("x-octopus-unresolved=true"));
    }
    @Test void lookupExcludesOtherRepositoriesAndNeverFallsBackToHead() {
        var c=add("app.Api", "package app; class Api { String get(){return null;} }"); sources();
        var p=AnalyzedProjectEntity.builder().id(1L).repoConfigId(10L).build();
        var other=AnalyzedProjectEntity.builder().id(2L).repoConfigId(20L).build();
        when(projects.findById(1L)).thenReturn(Optional.of(p)); when(projects.findAll()).thenReturn(List.of(p,other));
        when(classes.findByProjectIdAndBranchAndCommitId(1L,"feature",COMMIT)).thenReturn(snapshot);
        when(query.getEndpoints(1L,"feature",COMMIT)).thenReturn(List.of(new EndpointDTO("GET","/x","get","app.Api","Api","app.Api.get():String",1L,"app","feature",COMMIT)));
        when(methods.findByClassInfoId(c.getId())).thenReturn(List.of(MethodInfo.builder().methodName("get").methodSignature("app.Api.get():String").parameterTypes("[]").build()));
        assertEquals("feature",service.contract(1,"feature",COMMIT,"app.Api.get():String","GET","/x").branch());
        verify(classes,never()).findByProjectIdAndBranchAndCommitId(eq(2L),any(),any());
        verify(classes,never()).findByProjectIdAndBranchAndIsHeadTrue(any(),any());
        assertThrows(IllegalArgumentException.class,()->service.contract(1,"feature","HEAD","app.Api.get():String","GET","/x"));
        assertThrows(IllegalArgumentException.class,()->service.contract(1,"feature","b".repeat(40),"app.Api.get():String","GET","/x"));
    }
    @Test void sourceContextUsesPinnedEntryAndDtoEvidenceWithSanitizedLiterals() {
        var c=add("app.Api", "package app; class Api { @PostMapping(\"/x\") Req get(@RequestBody Req req) { String password=\"fixture-password\"; return req; } void unrelated() { destroy(); } }");
        add("app.Req", "package app; class Req { String name; String hiddenDefault=\"fixture-secret\"; void debug(){ System.out.println(\"secret-log\"); } }"); sources();
        var p=AnalyzedProjectEntity.builder().id(1L).repoConfigId(10L).build();
        when(projects.findById(1L)).thenReturn(Optional.of(p)); when(projects.findAll()).thenReturn(List.of(p));
        when(classes.findByProjectIdAndBranchAndCommitId(1L,"feature",COMMIT)).thenReturn(snapshot);
        when(query.getEndpoints(1L,"feature",COMMIT)).thenReturn(List.of(new EndpointDTO("POST","/x","get","app.Api","Api","app.Api.get(app.Req):app.Req",1L,"app","feature",COMMIT)));
        when(methods.findByClassInfoId(c.getId())).thenReturn(List.of(MethodInfo.builder().methodName("get").methodSignature("app.Api.get(app.Req):app.Req").parameterTypes("[\"app.Req\"]").build()));
        var context=service.sourceContext(1,"feature",COMMIT,"app.Api.get(app.Req):app.Req","POST","/x");
        assertTrue(context.sources().size()>=2); assertTrue(context.sources().stream().allMatch(e->COMMIT.equals(e.commitId())&&e.lineStart()>0));
        var text=context.sources().toString(); assertTrue(text.contains("ENTRY_METHOD")); assertTrue(text.contains("return req")); assertTrue(text.contains("String name"));
        for(String excluded:List.of("fixture-password","fixture-secret","secret-log","destroy()")) assertFalse(text.contains(excluded),excluded);
        assertTrue(context.warnings().stream().anyMatch(w->w.contains("网关")));
        verify(source,atLeastOnce()).fetchBatch(eq(1L),eq(COMMIT),anyList());
    }
}
