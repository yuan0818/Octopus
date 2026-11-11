package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingJson.*;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Resolves persisted repository identity, never module names supplied by a browser or model. */
@Component
public class RepositoryCatalogClient {
    public record Module(long projectId, String name, String modulePath, String branch, List<String> branches) {}
    public record Scope(String key, String name, String rootPath, List<Module> modules) {}
    public record Version(String commitId, List<Long> projectIds, List<Long> missingProjectIds) {}
    public record Endpoint(long projectId, String branch, String commitId, String signature, String httpMethod, String path) {}
    public record Contract(long projectId, String branch, String commitId, String signature, String httpMethod, String path,
                           Map<String, TestingModel.Port> inputs, Map<String, Object> requestSchema, Map<String, Object> responseSchema, List<String> warnings) {}
    public record SourceEvidence(String id, long projectId, String commitId, String filePath, int lineStart, int lineEnd, String kind, String text, boolean truncated) {}
    public record SourceContext(Contract contract, List<SourceEvidence> sources, List<String> warnings) {}
    public record BackgroundPacket(String repositoryKey,String name,String branch,String commitId,String environment,List<Module> modules,List<SourceEvidence> sources,List<String> warnings) {}
    public record Inventory(String repositoryKey,String name,String branch,String commitId,String environment,List<Module> modules,List<String> files,List<String> warnings) {}
    public Inventory inventory(String key,String branch,String commit,String environment) {
        return GSON.fromJson(get("/api/query/repository-exploration?key="+enc(key)+"&branch="+enc(branch)+"&commitId="+enc(commit)),Inventory.class);
    }
    public List<SourceEvidence> readFiles(RepositoryAnalysisService.Request r,List<String> files) {
        try {
            var body=Map.of("repositoryKey",r.repositoryKey(),"branch",r.branch(),"commitId",r.commitId(),"files",files);
            var response=http.send(HttpRequest.newBuilder(URI.create(url+"/api/query/repository-exploration/read")).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(canonical(body))).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new FlowFailure(FlowFailure.Code.CONTEXT_UNAVAILABLE);
            var root=JsonParser.parseString(response.body()).getAsJsonObject();
            if(!root.has("data") || root.get("data").isJsonNull())throw new FlowFailure(FlowFailure.Code.CONTEXT_UNAVAILABLE);
            return Arrays.asList(GSON.fromJson(root.get("data"),SourceEvidence[].class));
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new FlowFailure(FlowFailure.Code.WORKER_INTERRUPTED);}
        catch(java.io.IOException e){throw new FlowFailure(FlowFailure.Code.CONTEXT_UNAVAILABLE);}
    }
    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public RepositoryCatalogClient(@Value("${scenario.testing.query-url:http://localhost:8080}") String url) { this.url = url; }
    public List<Scope> scopes() { return Arrays.asList(GSON.fromJson(get("/api/query/repository-scopes"), Scope[].class)); }
    public Scope scope(String key) {
        return scopes().stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请选择已分析的项目"));
    }
    public Scope validate(String key, String module) {
        Scope scope = scope(key);
        if (module != null && !module.isEmpty() && scope.modules().stream().noneMatch(m -> m.modulePath().equals(module)))
            throw new IllegalArgumentException("模块不属于所选项目");
        return scope;
    }
    public List<Version> versions(String key, String branch) {
        return Arrays.asList(GSON.fromJson(get("/api/query/repository-scopes/versions?key=" + enc(key) + "&branch=" + enc(branch)), Version[].class));
    }
    public BackgroundPacket repositoryBackground(String key,String branch,String commit,String environment) {
        return GSON.fromJson(get("/api/query/repository-background?key="+enc(key)+"&branch="+enc(branch)+"&commitId="+enc(commit)+"&environment="+enc(environment)),BackgroundPacket.class);
    }
    public List<Endpoint> endpoints(String branch, String commit) {
        return Arrays.asList(GSON.fromJson(get("/api/query/endpoints?branch=" + enc(branch) + "&commitId=" + enc(commit)), Endpoint[].class));
    }
    public Contract contract(TestingModel.Implementation implementation, String method, String path) {
        return GSON.fromJson(get("/api/query/endpoint-contract?projectId=" + implementation.projectId()
                + "&branch=" + enc(implementation.branch()) + "&commitId=" + enc(implementation.commitId())
                + "&signature=" + enc(implementation.signature()) + "&httpMethod=" + enc(method) + "&path=" + enc(path)), Contract.class);
    }
    public SourceContext sourceContext(TestingModel.Implementation implementation, String method, String path) {
        return GSON.fromJson(get("/api/query/endpoint-contract/source-context?projectId=" + implementation.projectId()
                + "&branch=" + enc(implementation.branch()) + "&commitId=" + enc(implementation.commitId())
                + "&signature=" + enc(implementation.signature()) + "&httpMethod=" + enc(method) + "&path=" + enc(path)), SourceContext.class);
    }
    private JsonElement get(String path) {
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new FlowFailure(FlowFailure.Code.CONTEXT_UNAVAILABLE,response.statusCode(),null);
            var body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!body.has("data") || body.get("data").isJsonNull()) throw new IllegalStateException("代码目录未返回有效数据");
            return body.get("data");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("代码目录查询已中断");
        } catch (java.io.IOException e) { throw new FlowFailure(FlowFailure.Code.CONTEXT_UNAVAILABLE); }
    }
    private static String enc(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
}
