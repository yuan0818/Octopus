package io.yuan.octopus.review.requirements;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** 需求源适配器共享的轻量 HTTP 工具（与 HttpLLMAdapter 一致使用 JDK HttpClient）。 */
final class SourceHttp {

    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private SourceHttp() {}

    static JsonObject getJson(String url, String basicAuth, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .header("Accept", "application/json");
        if (basicAuth != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(basicAuth.getBytes(StandardCharsets.UTF_8)));
        }
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return execute(builder.build());
    }

    static JsonObject postJson(String url, Map<String, Object> body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return execute(request);
    }

    private static JsonObject execute(HttpRequest request) {
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalArgumentException(
                        "HTTP " + response.statusCode() + " from " + request.uri() + ": " + abbreviate(response.body()));
            }
            JsonObject parsed = GSON.fromJson(response.body(), JsonObject.class);
            return parsed != null ? parsed : new JsonObject();
        } catch (IOException e) {
            throw new IllegalArgumentException("无法连接 " + request.uri() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("请求被中断: " + request.uri());
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
