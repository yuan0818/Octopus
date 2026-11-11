package io.yuan.octopus.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.entity.RepoSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
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
 * 批量从 code-reconstruct 按行范围读取 git 源码片段。
 * <p>
 * git 是源码内容的唯一真相来源：链路版本对比等消费方用元数据里的坐标
 * （file_path + line_start/line_end）到这里换取该 commit 的真实文本，
 * 不读取任何存储的源码副本。
 */
@Service
public class GitMethodSourceClient {

    private static final Logger log = LoggerFactory.getLogger(GitMethodSourceClient.class);
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final HttpClient httpClient;

    public GitMethodSourceClient(@Value("${octopus.source-reconstruct.url:http://localhost:8082}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public record SourceItem(String filePath, int lineStart, int lineEnd) {}

    public List<String> tree(long projectId, String commitId) {
        if (commitId == null || !commitId.matches("[a-fA-F0-9]{40}")) throw new IllegalArgumentException("需要完整提交编号");
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/source/tree?projectId=" + projectId + "&commitId=" + commitId)).timeout(Duration.ofSeconds(30)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("仓库目录服务不可用");
            var root = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.get("success").getAsBoolean()) throw new IllegalStateException("指定提交目录不可读取");
            return java.util.Arrays.asList(GSON.fromJson(root.get("data"), String[].class));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("读取目录已中断"); }
        catch (java.io.IOException e) { throw new IllegalStateException("仓库目录服务不可用"); }
    }

    /**
     * @return 按 items 顺序的文本列表；git 不可达/条目未找到时对应位置为 null
     *         （调用方自行决定回退策略）
     */
    public List<String> fetchBatch(long projectId, String commitId, List<SourceItem> items) {
        if (commitId == null || commitId.isBlank() || items == null || items.isEmpty()) {
            return new ArrayList<>(List.of(new String[items == null ? 0 : items.size()]));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("commitId", commitId);
        List<Map<String, Object>> payload = new ArrayList<>(items.size());
        for (SourceItem item : items) {
            payload.add(Map.of(
                    "filePath", item.filePath(),
                    "lineStart", item.lineStart(),
                    "lineEnd", item.lineEnd()));
        }
        body.put("items", payload);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/source/batch"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("source/batch returned {}: {}", response.statusCode(), abbreviate(response.body()));
                return nulls(items.size());
            }
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> root = GSON.fromJson(response.body(), mapType);
            if (!Boolean.TRUE.equals(root.get("success"))) {
                log.warn("source/batch failed: {}", root.get("message"));
                return nulls(items.size());
            }
            List<?> data = (List<?>) root.get("data");
            List<String> out = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                Object rowObj = data != null && i < data.size() ? data.get(i) : null;
                Map<?, ?> row = rowObj instanceof Map<?, ?> m ? m : Map.of();
                Object content = row.get("content");
                out.add(Boolean.TRUE.equals(row.get("found")) && content instanceof String s ? s : null);
            }
            return out;
        } catch (Exception e) {
            log.warn("source/batch unreachable ({}): {} — 消费方将回退存储快照", baseUrl, e.getMessage());
            return nulls(items.size());
        }
    }

    private static List<String> nulls(int size) {
        return new ArrayList<>(java.util.Collections.nCopies(size, null));
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() <= 200 ? s : s.substring(0, 200);
    }
}
