package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Uses the existing Agent model registry and adapters through the review service. */
@Component
public class FlowModelClient {
    public record Model(String name, String provider, String modelId) {}
    public record Response(String content, String model) {}
    private final String url;
    private final Duration requestTimeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @org.springframework.beans.factory.annotation.Autowired
    public FlowModelClient(@Value("${scenario.testing.review-url:http://localhost:8083}") String url) { this(url,Duration.ofSeconds(260)); }
    FlowModelClient(String url,Duration timeout) { this.url=url;this.requestTimeout=timeout; }
    public List<Model> models() { return Arrays.asList(read(send("/models", null), Model[].class)); }
    public Response suggest(String model, String goal, List<Map<String, Object>> background, List<Map<String, Object>> operations, Map<String, Object> path) {
        return read(send("/suggest", Map.of("model", model == null ? "" : model, "goal", goal, "background", background, "operations", operations, "path", path)), Response.class);
    }
    public Response verification(String model, Map<String,Object> context) {
        return read(send("/verification", Map.of("model", model == null ? "" : model, "context", context)), Response.class);
    }
    public Response planOrdered(String model,Map<String,Object> context) {
        return read(send("/plan-ordered",Map.of("model",model==null?"":model,"context",context)),Response.class);
    }
    public Response step(String model, Map<String, Object> context) { return step(model, context, null); }
    /** timeout 非空时同时约束本次 HTTP 调用与 review-agent 内的模型调用（ModelDeadline）。 */
    public Response step(String model, Map<String, Object> context, Duration timeout) {
        String body=timeout==null ? send("/step-suggest", Map.of("model", model == null ? "" : model, "context", context))
                : send("/step-suggest", bounded(model, context, timeout), timeout);
        try { var result=read(body,Response.class);if(result==null || result.content()==null)throw new IllegalArgumentException();return result; }
        catch(RuntimeException e) { throw new FlowFailure(FlowFailure.Code.MODEL_RESPONSE_INVALID); }
    }
    public Response repositoryBackground(String model,RepositoryCatalogClient.BackgroundPacket packet) {
        return read(send("/repository-background",Map.of("model",model==null?"":model,"context",packet)),Response.class);
    }
    public Response repositoryChat(String model,Map<String,Object> context) {
        return read(send("/repository-chat",Map.of("model",model==null?"":model,"context",context)),Response.class);
    }
    public Response negativeCases(String model,Map<String,Object> context) {
        return read(send("/negative-cases",Map.of("model",model==null?"":model,"context",context),Duration.ofSeconds(200)),Response.class);
    }
    /** 三角色用例生成：Planner 规划与 Operator 数据位填充。响应带提示模板版本，记入角色调用审计（TRACE-002）。 */
    public record VersionedResponse(String content, String model, String promptVersion) {}
    public static final Duration CASE_CALL = Duration.ofSeconds(200);
    public VersionedResponse casePlan(String model,Map<String,Object> context) { return casePlan(model,context,CASE_CALL); }
    public VersionedResponse casePlan(String model,Map<String,Object> context,Duration timeout) {
        return read(send("/case-plan",bounded(model,context,timeout),timeout),VersionedResponse.class);
    }
    public VersionedResponse caseMaterialize(String model,Map<String,Object> context) { return caseMaterialize(model,context,CASE_CALL); }
    public VersionedResponse caseMaterialize(String model,Map<String,Object> context,Duration timeout) {
        return read(send("/case-materialize",bounded(model,context,timeout),timeout),VersionedResponse.class);
    }
    /** review-agent 的模型调用比本端 HTTP 超时早 5 秒结束，避免对方仍在调用模型而本端已放弃。 */
    public static Map<String,Object> bounded(String model,Map<String,Object> context,Duration timeout) {
        var body=new LinkedHashMap<String,Object>();
        body.put("model",model==null?"":model);body.put("context",context);
        body.put("timeoutSeconds",Math.max(5,(int)timeout.toSeconds()-5));
        return body;
    }
    public Response runTriage(String model,Map<String,Object> context) {
        return read(send("/run-triage",Map.of("model",model==null?"":model,"context",context),Duration.ofSeconds(200)),Response.class);
    }
    public Response repositoryStage(String model,Map<String,Object> context) {
        return read(send("/repository-stage",Map.of("model",model==null?"":model,"context",context),"SYNTHESIZE".equals(context.get("stage"))?Duration.ofSeconds(620):requestTimeout),Response.class);
    }
    private String send(String path, Object body) {
        return send(path,body,body==null?Duration.ofSeconds(10):requestTimeout);
    }
    /** Phase 1E：模型调用度量（可选注入；失败不影响调用）。 */
    private SuggestionMetricsService metrics;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMetrics(SuggestionMetricsService metrics) { this.metrics = metrics; }

    /**
     * SO-010：一次格式无效的模型响应（进入有界格式修正之前记录）。格式修正率 = kind_FORMAT 的失败数 ÷ 对应 kind 的调用数；
     * refId 记录提示版本，版本后缀说明输出模式（例如 case-plan@1+schema@1）。
     */
    public void recordFormatInvalid(String kind, String model, String promptVersion) {
        if (metrics != null) metrics.record(kind + "_FORMAT", "FAILED", model, promptVersion, null, null, null, "FORMAT_INVALID");
    }

    private static String metricKind(String path) {
        return switch (path) {
            case "/suggest" -> "FLOW_SUGGEST";
            case "/step-suggest" -> "STEP";
            case "/verification" -> "VERIFICATION";
            case "/plan-ordered" -> "ORDERED_PLAN";
            case "/repository-background" -> "REPOSITORY_BACKGROUND";
            case "/repository-chat" -> "REPOSITORY_CHAT";
            case "/repository-stage" -> "REPOSITORY_STAGE";
            case "/negative-cases" -> "NEGATIVE_CASES";
            case "/case-plan" -> "CASE_PLAN";
            case "/case-materialize" -> "CASE_MATERIALIZE";
            default -> null;   // /models 不计；/run-triage 由 RunTriageService 自行记录（含输出校验结果）
        };
    }

    private String send(String path,Object body,Duration timeout) {
        String kind = body == null ? null : metricKind(path);
        String requestedModel = body instanceof Map<?,?> map && map.get("model") instanceof String m ? m : null;
        long started = System.nanoTime();
        try {
            String content = sendRaw(path, body, timeout);
            if (kind != null && metrics != null) metrics.record(kind, "GENERATED", requestedModel, null,
                    (System.nanoTime() - started) / 1_000_000, content == null ? 0 : content.length(), null, null);
            return content;
        } catch (FlowFailure e) {
            if (kind != null && metrics != null) metrics.record(kind, "FAILED", requestedModel, null,
                    (System.nanoTime() - started) / 1_000_000, null, null, e.code == null ? "MODEL_UNAVAILABLE" : e.code.name());
            throw e;
        }
    }

    private String sendRaw(String path,Object body,Duration timeout) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url + "/api/business-flow" + path)).timeout(timeout);
            if (body == null) builder.GET(); else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(canonical(body)));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                var code=switch(response.statusCode()) { case 401,403 -> FlowFailure.Code.MODEL_AUTH; case 429 -> FlowFailure.Code.MODEL_RATE_LIMIT; case 408,504 -> FlowFailure.Code.MODEL_TIMEOUT; default -> FlowFailure.Code.MODEL_UNAVAILABLE; };
                Integer upstream=response.statusCode();
                try {
                    var bodyJson=com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
                    var candidate=FlowFailure.Code.valueOf(bodyJson.get("code").getAsString());
                    if(candidate.name().startsWith("MODEL_"))code=candidate;
                    if(bodyJson.has("upstreamStatus"))upstream=bodyJson.get("upstreamStatus").isJsonNull()?null:bodyJson.get("upstreamStatus").getAsInt();
                } catch(RuntimeException ignored) { /* Never display raw error bodies. */ }
                throw new FlowFailure(code,upstream,null);
            }
            return response.body();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new FlowFailure(FlowFailure.Code.WORKER_INTERRUPTED); }
        catch (HttpTimeoutException e) { throw new FlowFailure(FlowFailure.Code.MODEL_TIMEOUT); }
        catch (java.io.IOException e) { throw new FlowFailure(FlowFailure.Code.MODEL_UNAVAILABLE); }
    }
}
