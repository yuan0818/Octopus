package io.yuan.octopus.review.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.yuan.octopus.review.adapter.LLMAdapter.LLMResponse;
import io.yuan.octopus.review.dto.ChangeSummaryRequest;
import io.yuan.octopus.review.dto.LLMConfig;
import io.yuan.octopus.review.model.ModelConfig;
import io.yuan.octopus.review.model.ModelRegistry;
import io.yuan.octopus.review.prompt.PromptManager;
import io.yuan.octopus.review.prompt.PromptTemplate;
import io.yuan.octopus.review.skill.Skill;
import io.yuan.octopus.review.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 变更总结：同一入口在两个版本（分支/commit/tag 任意组合）之间。
 * <p>
 * 方案：不做服务端预 diff——分别拉取两个版本的【整条链路重建源码】
 * （git 优先），完整交给模型，由模型理解意图、自行 diff、总结修改要点
 * 并生成针对修改的测试要点；无实质改动时照实返回空结论。
 * 同时产出成员级双版本源码清单，供前端以 git-diff 风格展示。
 */
@Service
public class ChangeSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ChangeSummaryService.class);
    private static final Gson GSON = new Gson();
    /** 单方法注入上限与单版本链路总上限（控制 token）。 */
    private static final int MAX_METHOD_CHARS = 4000;
    private static final int MAX_VERSION_CHARS = 60_000;

    private final String queryServiceUrl;
    private final HttpClient httpClient;
    private final PromptManager promptManager;
    private final SkillRegistry skillRegistry;
    private final LLMIntegrationService llmIntegrationService;
    private final ModelRegistry modelRegistry;

    public ChangeSummaryService(
            @Value("${review.query-service.url:${QUERY_SERVICE_URL:http://localhost:8080}}") String queryServiceUrl,
            PromptManager promptManager,
            SkillRegistry skillRegistry,
            LLMIntegrationService llmIntegrationService,
            ModelRegistry modelRegistry) {
        this.queryServiceUrl = queryServiceUrl;
        this.promptManager = promptManager;
        this.skillRegistry = skillRegistry;
        this.llmIntegrationService = llmIntegrationService;
        this.modelRegistry = modelRegistry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public record SummaryPoint(String title, String detail) {}
    public record TestPoint(String scenario, String reason) {}
    /** 成员级双版本源码（前端 git-diff 风格展示用）。status: ADDED/REMOVED/CHANGED/UNCHANGED。 */
    public record MemberDiff(String signature, String label, String status,
                             String fromSource, String toSource) {}
    public record ChangeSummaryResult(
            String anchor, String fromVersion, String toVersion,
            int addedCount, int removedCount, int changedCount, int unchangedCount, boolean gitBacked,
            List<MemberDiff> members,
            List<SummaryPoint> highlights, List<TestPoint> testPoints,
            String rawAnswer, String model) {}

    public ChangeSummaryResult summarize(ChangeSummaryRequest request) {
        // 1. 链路成员差集（用于计数与前端 status；源码不依赖它）
        JsonObject diff = fetchJson(entryChainDiffUrl(request));

        // 2. 两个版本的整条链路重建源码
        List<Map<String, Object>> fromChain = fetchJsonList(entryChainSourceUrl(
                request.getProjectId(), request.getClassName(), request.getMethodName(),
                request.getFromBranch(), request.getFromCommitId()));
        List<Map<String, Object>> toChain = fetchJsonList(entryChainSourceUrl(
                request.getProjectId(), request.getClassName(), request.getMethodName(),
                request.getToBranch(), request.getToCommitId()));

        // 3. 成员级配对（canonical 签名）→ UI diff 数据 + 计数
        Map<String, Map<String, Object>> fromBySig = byCanonical(fromChain);
        Map<String, Map<String, Object>> toBySig = byCanonical(toChain);
        List<MemberDiff> members = new ArrayList<>();
        int added = 0, removed = 0, changed = 0, unchanged = 0;
        for (Map.Entry<String, Map<String, Object>> e : toBySig.entrySet()) {
            String sig = e.getKey();
            String label = labelOf(e.getValue());
            String toSrc = str(e.getValue().get("source"));
            Map<String, Object> fromRow = fromBySig.get(sig);
            if (fromRow == null) {
                added++;
                members.add(new MemberDiff(sig, label, "ADDED", null, toSrc));
            } else {
                String fromSrc = str(fromRow.get("source"));
                if (java.util.Objects.equals(fromSrc, toSrc)) {
                    unchanged++;
                    members.add(new MemberDiff(sig, label, "UNCHANGED", fromSrc, toSrc));
                } else {
                    changed++;
                    members.add(new MemberDiff(sig, label, "CHANGED", fromSrc, toSrc));
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : fromBySig.entrySet()) {
            if (!toBySig.containsKey(e.getKey())) {
                removed++;
                members.add(new MemberDiff(e.getKey(), labelOf(e.getValue()), "REMOVED",
                        str(e.getValue().get("source")), null));
            }
        }

        // 4. 组 prompt（skill: change-summary）——双版本全链路源码交给模型自行 diff
        Skill skill = skillRegistry.resolve("change-summary");
        PromptTemplate template = promptManager.get(skill.getPromptTemplateId());
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("anchor", request.getClassName() + "#" + request.getMethodName());
        variables.put("fromVersion", versionLabel(request.getFromBranch(), request.getFromCommitId()));
        variables.put("toVersion", versionLabel(request.getToBranch(), request.getToCommitId()));
        variables.put("membership", membershipBrief(added, removed, changed, unchanged));
        variables.put("fromChainSource", formatChain(fromChain, "基准"));
        variables.put("toChainSource", formatChain(toChain, "目标"));
        String userPrompt = template.renderUserPrompt(variables);
        String systemPrompt = skill.enrichSystemPrompt(template.systemPrompt());

        // 5. LLM
        LLMConfig config = resolveLLMConfig(request);
        LLMResponse response = llmIntegrationService.call(systemPrompt, userPrompt, config);

        // 6. 解析（宽松；解析成功且为空数组 = 模型判定无实质改动，照实返回）
        Parsed parsed = parse(response.content());
        return new ChangeSummaryResult(
                request.getClassName() + "#" + request.getMethodName(),
                versionLabel(request.getFromBranch(), request.getFromCommitId()),
                versionLabel(request.getToBranch(), request.getToCommitId()),
                added, removed, changed, unchanged,
                Boolean.TRUE.equals(diff.get("gitBacked") instanceof JsonElement je && je.getAsBoolean()),
                members,
                parsed.highlights(), parsed.testPoints(),
                response.content(), response.model());
    }

    // ── code-intelligence 调用 ────────────────────────────────────────────

    private String entryChainDiffUrl(ChangeSummaryRequest r) {
        return queryServiceUrl + "/api/query/entry-chain-diff"
                + "?projectId=" + r.getProjectId()
                + "&className=" + urlEncode(r.getClassName())
                + "&methodName=" + urlEncode(r.getMethodName())
                + "&fromBranch=" + urlEncode(nullToEmpty(r.getFromBranch()))
                + "&toBranch=" + urlEncode(nullToEmpty(r.getToBranch()))
                + (blank(r.getFromCommitId()) ? "" : "&fromCommitId=" + urlEncode(r.getFromCommitId()))
                + (blank(r.getToCommitId()) ? "" : "&toCommitId=" + urlEncode(r.getToCommitId()));
    }

    private String entryChainSourceUrl(Long projectId, String className, String methodName,
                                       String branch, String commitId) {
        return queryServiceUrl + "/api/query/entry-chain-source"
                + "?projectId=" + projectId
                + "&className=" + urlEncode(className)
                + "&methodName=" + urlEncode(methodName)
                + "&branch=" + urlEncode(nullToEmpty(branch))
                + (blank(commitId) ? "" : "&commitId=" + urlEncode(commitId));
    }

    private JsonObject fetchJson(String url) {
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject root = GSON.fromJson(response.body(), JsonObject.class);
            if (response.statusCode() != 200 || !success(root)) {
                throw new IllegalArgumentException("查询失败: " + strOr(root, "message", "HTTP " + response.statusCode()));
            }
            return root.getAsJsonObject("data");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法访问查询服务: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchJsonList(String url) {
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject root = GSON.fromJson(response.body(), JsonObject.class);
            if (response.statusCode() != 200 || !success(root)) {
                throw new IllegalArgumentException("链路源码查询失败: " + strOr(root, "message", "HTTP " + response.statusCode()));
            }
            JsonElement data = root.get("data");
            return data instanceof JsonArray a
                    ? GSON.fromJson(a, List.class)
                    : new ArrayList<>();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法访问查询服务: " + e.getMessage(), e);
        }
    }

    private static boolean success(JsonObject root) {
        JsonElement e = root.get("success");
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    // ── prompt 组装 ───────────────────────────────────────────────────────

    private static Map<String, Map<String, Object>> byCanonical(List<Map<String, Object>> chain) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> m : chain) {
            out.putIfAbsent(canonical(str(m.get("signature"))), m);
        }
        return out;
    }

    /** 与 code-intelligence 的 SignatureNormalizer 同规范：", " → ","。 */
    private static String canonical(String sig) {
        return sig == null ? "" : sig.replace(", ", ",");
    }

    private static String labelOf(Map<String, Object> row) {
        String cls = str(row.get("qualifiedClassName"));
        String simple = cls != null && cls.contains(".")
                ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
        return simple + "#" + str(row.get("methodName"));
    }

    private static String membershipBrief(int added, int removed, int changed, int unchanged) {
        return ("链路成员配对（服务端按签名统计，仅供参考，以你自己的源码对比为准）：\n"
                + "新增 %d / 移除 %d / 源码变更 %d / 未变化 %d%n").formatted(added, removed, changed, unchanged);
    }

    /** 一个版本的链路源码清单：每个方法 `#### 类#方法` + 代码块。 */
    private static String formatChain(List<Map<String, Object>> chain, String tag) {
        if (chain == null || chain.isEmpty()) {
            return "（" + tag + "版本链路为空：入口不存在）";
        }
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map<String, Object> m : chain) {
            String label = labelOf(m);
            String src = abbreviate(str(m.get("source")), MAX_METHOD_CHARS);
            if (src == null || src.isBlank()) {
                src = "（源码不可得）";
            }
            String block = "#### " + label + "\n```\n" + src + "\n```\n";
            if (total + block.length() > MAX_VERSION_CHARS) {
                sb.append("…（其余 ").append(chain.size()).append(" 个成员中部分已截断）\n");
                break;
            }
            sb.append(block);
            total += block.length();
        }
        return sb.toString();
    }

    // ── LLM 输出解析 ──────────────────────────────────────────────────────

    private record Parsed(boolean ok, List<SummaryPoint> highlights, List<TestPoint> testPoints) {}

    private Parsed parse(String content) {
        List<SummaryPoint> highlights = new ArrayList<>();
        List<TestPoint> testPoints = new ArrayList<>();
        try {
            String json = extractJson(content);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            for (JsonElement e : arrayOrEmpty(root, "highlights")) {
                JsonObject o = e.getAsJsonObject();
                highlights.add(new SummaryPoint(strOr(o, "title", ""), strOr(o, "detail", "")));
            }
            for (JsonElement e : arrayOrEmpty(root, "testPoints")) {
                JsonObject o = e.getAsJsonObject();
                testPoints.add(new TestPoint(strOr(o, "scenario", ""), strOr(o, "reason", "")));
            }
            return new Parsed(true, highlights, testPoints);
        } catch (Exception e) {
            log.warn("变更总结输出解析失败，回退原文: {}", e.getMessage());
            highlights.add(new SummaryPoint("总结（原文）", content));
            return new Parsed(false, highlights, testPoints);
        }
    }

    private static String extractJson(String content) {
        if (content == null) return "{}";
        int fence = content.indexOf("```json");
        if (fence >= 0) {
            int start = content.indexOf('\n', fence) + 1;
            int end = content.indexOf("```", start);
            return end > start ? content.substring(start, end) : content.substring(start);
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : content;
    }

    private static JsonArray arrayOrEmpty(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e instanceof JsonArray a ? a : new JsonArray();
    }

    private static String strOr(JsonObject obj, String key, String def) {
        JsonElement e = obj.get(key);
        return e != null && !e.isJsonNull() ? e.getAsString() : def;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static String versionLabel(String branch, String commitId) {
        String label = branch != null ? branch : "?";
        if (!blank(commitId)) {
            label += "@" + commitId.substring(0, Math.min(8, commitId.length()));
        }
        return label;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…(截断)";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private LLMConfig resolveLLMConfig(ChangeSummaryRequest request) {
        ModelConfig modelConfig = null;
        if (!blank(request.getModelName())) {
            modelConfig = modelRegistry.get(request.getModelName());
        }
        if (modelConfig == null) {
            modelConfig = modelRegistry.selectWithFallback("general");
        }
        if (modelConfig == null) {
            throw new IllegalArgumentException("No model configured. Register one in Model Management first.");
        }
        LLMConfig config = new LLMConfig();
        config.setProvider(modelConfig.getProvider());
        config.setApiFormat(modelConfig.getApiFormat());
        config.setModel(modelConfig.getModelId());
        config.setApiKey(modelConfig.getApiKey());
        config.setApiUrl(modelConfig.getApiUrl());
        config.setMaxTokens(Math.min(modelConfig.getMaxTokens(), 8000));
        config.setTemperature(modelConfig.getTemperature());
        return config;
    }
}
