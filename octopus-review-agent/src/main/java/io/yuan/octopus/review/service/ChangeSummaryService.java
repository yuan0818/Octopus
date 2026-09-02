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
 * 变更总结：同一入口在两个分析版本之间的「修改要点 + 测试要点」。
 * <p>
 * 链路差异来自 code-intelligence 的 entry-chain-diff（git-backed：
 * 变更文本从 git blob 按坐标切片），本服务只负责把 diff 组织成
 * prompt（skill：change-summary）并解析 LLM 输出。
 */
@Service
public class ChangeSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ChangeSummaryService.class);
    private static final Gson GSON = new Gson();
    /** 每个 changed 方法注入 prompt 的源码片段上限（防 token 爆炸）。 */
    private static final int MAX_SNIPPET_CHARS = 4000;
    private static final int MAX_CHANGED_METHODS = 30;

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
    public record ChangeSummaryResult(
            String anchor, String fromVersion, String toVersion,
            int addedCount, int removedCount, int changedCount, boolean gitBacked,
            List<SummaryPoint> highlights, List<TestPoint> testPoints,
            String rawAnswer, String model) {}

    public ChangeSummaryResult summarize(ChangeSummaryRequest request) {
        // 1. 链路差异（git-backed，来自 code-intelligence）
        JsonObject diff = fetchEntryChainDiff(request);

        // 2. 组 prompt（skill: change-summary）
        Skill skill = skillRegistry.resolve("change-summary");
        PromptTemplate template = promptManager.get(skill.getPromptTemplateId());
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("anchor", request.getClassName() + "#" + request.getMethodName());
        variables.put("fromVersion", versionLabel(request.getFromBranch(), request.getFromCommitId()));
        variables.put("toVersion", versionLabel(request.getToBranch(), request.getToCommitId()));
        variables.put("chainDiff", formatDiff(diff));
        String userPrompt = template.renderUserPrompt(variables);
        String systemPrompt = template.systemPrompt();

        // 3. LLM
        LLMConfig config = resolveLLMConfig(request);
        LLMResponse response = llmIntegrationService.call(systemPrompt, userPrompt, config);

        // 4. 解析（宽松：解析失败回退原文）
        Parsed parsed = parse(response.content());
        return new ChangeSummaryResult(
                request.getClassName() + "#" + request.getMethodName(),
                versionLabel(request.getFromBranch(), request.getFromCommitId()),
                versionLabel(request.getToBranch(), request.getToCommitId()),
                sizeOf(diff, "added"), sizeOf(diff, "removed"), sizeOf(diff, "changed"),
                Boolean.TRUE.equals(diff.get("gitBacked") != null && diff.get("gitBacked").getAsBoolean()),
                parsed.highlights(), parsed.testPoints(),
                response.content(), response.model());
    }

    // ── diff 获取与格式化 ─────────────────────────────────────────────────

    private JsonObject fetchEntryChainDiff(ChangeSummaryRequest request) {
        String url = queryServiceUrl + "/api/query/entry-chain-diff"
                + "?projectId=" + request.getProjectId()
                + "&className=" + urlEncode(request.getClassName())
                + "&methodName=" + urlEncode(request.getMethodName())
                + "&fromBranch=" + urlEncode(nullToEmpty(request.getFromBranch()))
                + "&toBranch=" + urlEncode(nullToEmpty(request.getToBranch()))
                + (blank(request.getFromCommitId()) ? "" : "&fromCommitId=" + urlEncode(request.getFromCommitId()))
                + (blank(request.getToCommitId()) ? "" : "&toCommitId=" + urlEncode(request.getToCommitId()));
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonObject root = GSON.fromJson(response.body(), JsonObject.class);
            if (response.statusCode() != 200 || !Boolean.TRUE.equals(root.get("success") instanceof JsonElement e && e.getAsBoolean())) {
                throw new IllegalArgumentException("链路差异查询失败: " + strOr(root, "message", "HTTP " + response.statusCode()));
            }
            return root.getAsJsonObject("data");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法访问链路差异服务: " + e.getMessage(), e);
        }
    }

    private String formatDiff(JsonObject diff) {
        StringBuilder sb = new StringBuilder();
        sb.append("gitBacked: ").append(diff.get("gitBacked")).append('\n');

        appendRefs(sb, "新增方法（仅目标版本存在）", diff.getAsJsonArray("added"));
        appendRefs(sb, "移除方法（仅基准版本存在）", diff.getAsJsonArray("removed"));

        JsonArray changed = diff.getAsJsonArray("changed");
        if (changed == null) {
            changed = new JsonArray();
        }
        sb.append("变更方法（两版本源码不同）：").append(changed.size()).append(" 个\n");
        int limit = Math.min(changed.size(), MAX_CHANGED_METHODS);
        for (int i = 0; i < limit; i++) {
            JsonObject m = changed.get(i).getAsJsonObject();
            JsonObject method = m.getAsJsonObject("method");
            sb.append("\n### ").append(strOr(method, "qualifiedClassName", "?"))
              .append("#").append(strOr(method, "methodName", "?")).append('\n');
            sb.append("基准版本源码:\n```").append(abbreviate(strOr(m, "fromSource", ""), MAX_SNIPPET_CHARS)).append("\n```\n");
            sb.append("目标版本源码:\n```").append(abbreviate(strOr(m, "toSource", ""), MAX_SNIPPET_CHARS)).append("\n```\n");
        }
        if (changed.size() > limit) {
            sb.append("（其余 ").append(changed.size() - limit).append(" 个变更方法已截断）\n");
        }
        sb.append("未变化方法数: ").append(diff.get("unchangedCount")).append('\n');
        return sb.toString();
    }

    private static void appendRefs(StringBuilder sb, String label, JsonArray refs) {
        int size = refs == null ? 0 : refs.size();
        sb.append(label).append("：").append(size).append(" 个\n");
        if (refs != null) {
            for (JsonElement ref : refs) {
                JsonObject r = ref.getAsJsonObject();
                sb.append("- ").append(strOr(r, "qualifiedClassName", "?"))
                  .append("#").append(strOr(r, "methodName", "?")).append('\n');
            }
        }
    }

    // ── LLM 输出解析 ──────────────────────────────────────────────────────

    private record Parsed(List<SummaryPoint> highlights, List<TestPoint> testPoints) {}

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
        } catch (Exception e) {
            log.warn("变更总结输出解析失败，回退原文展示: {}", e.getMessage());
        }
        if (highlights.isEmpty() && testPoints.isEmpty()) {
            highlights.add(new SummaryPoint("总结（原文）", content));
        }
        return new Parsed(highlights, testPoints);
    }

    /** 提取 ```json 围栏或裸 JSON 对象。 */
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

    private static int sizeOf(JsonObject diff, String key) {
        JsonElement e = diff.get(key);
        return e instanceof JsonArray a ? a.size() : 0;
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
