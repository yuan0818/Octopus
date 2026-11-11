package io.yuan.octopus.changereview.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import io.yuan.octopus.changereview.repository.ChangeReviewTaskRepository;
import io.yuan.octopus.entity.AnalyzedProjectEntity;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.exception.ResourceNotFoundException;
import io.yuan.octopus.exception.ValidationException;
import io.yuan.octopus.repository.AnalyzedProjectRepository;
import io.yuan.octopus.repository.RepoConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把变更审查任务接到业务回归（Phase 1B）。
 * <ol>
 *   <li>{@link #trigger}：用任务的 base / merge commit 在 scenario-runner 上发起批量回归，并附带回调地址。
 *       一般由 CI 在<b>部署到测试环境之后</b>调用（回归测的是已部署的服务，单纯的 patchset 事件不代表新代码已上线）；
 *       若团队为 patchset 提供预览环境，可开启 {@code auto-trigger} 在审查完成后自动发起。</li>
 *   <li>{@link #onCallback}：scenario-runner 批次结束后回调，校验共享 token，把摘要以评论回写到 Gerrit change。</li>
 * </ol>
 * externalRef 约定：{@code gerrit:<repoConfigId>:<changeNumber>:<patchsetNumber>:<taskId>}。
 */
@Service
public class ChangeRegressionService {

    private static final Logger log = LoggerFactory.getLogger(ChangeRegressionService.class);
    private static final Gson GSON = new Gson();

    private final ChangeReviewTaskRepository tasks;
    private final RepoConfigRepository repoConfigs;
    private final AnalyzedProjectRepository projects;
    private final GerritRestClient gerrit;
    private final RegressionCommentBuilder comments;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String scenarioUrl;
    private final String callbackBaseUrl;
    private final String callbackToken;
    private final String defaultEnvironment;
    private final String octopusBaseUrl;

    public ChangeRegressionService(ChangeReviewTaskRepository tasks,
                                   RepoConfigRepository repoConfigs,
                                   AnalyzedProjectRepository projects,
                                   GerritRestClient gerrit,
                                   RegressionCommentBuilder comments,
                                   @Value("${octopus.change-review.regression.scenario-url:http://localhost:8086}") String scenarioUrl,
                                   @Value("${octopus.change-review.regression.callback-base-url:${octopus.gerrit.octopus-base-url:http://localhost:8080}}") String callbackBaseUrl,
                                   @Value("${octopus.change-review.regression.callback-token:}") String callbackToken,
                                   @Value("${octopus.change-review.regression.environment:test}") String defaultEnvironment,
                                   @Value("${octopus.gerrit.octopus-base-url:http://localhost:8080}") String octopusBaseUrl) {
        this.tasks = tasks;
        this.repoConfigs = repoConfigs;
        this.projects = projects;
        this.gerrit = gerrit;
        this.comments = comments;
        this.scenarioUrl = scenarioUrl.replaceFirst("/$", "");
        this.callbackBaseUrl = callbackBaseUrl.replaceFirst("/$", "");
        this.callbackToken = callbackToken;
        this.defaultEnvironment = defaultEnvironment;
        this.octopusBaseUrl = octopusBaseUrl;
    }

    /** 为变更审查任务发起批量回归，返回 scenario-runner 的批次摘要。 */
    public Map<String, Object> trigger(Long taskId, String environment, Long timeoutSeconds) {
        ChangeReviewTask task = tasks.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Change review task not found: " + taskId));
        if (task.getBaseCommitId() == null || task.getMergeCommitId() == null) {
            throw new ValidationException("任务尚未完成合并与分析，无法确定回归的 base / target 版本");
        }
        List<Long> projectIds = projects.findByRepoConfigIdAndBranch(task.getRepoConfigId(), task.getTargetBranch())
                .stream().map(AnalyzedProjectEntity::getId).toList();
        if (projectIds.isEmpty()) throw new ValidationException("目标分支没有已分析的项目，无法计算影响范围");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("projectIds", projectIds);
        request.put("branch", task.getTargetBranch());
        request.put("baseCommitId", task.getBaseCommitId());
        request.put("targetCommitId", task.getMergeCommitId());
        request.put("environment", environment == null || environment.isBlank() ? defaultEnvironment : environment);
        if (timeoutSeconds != null) request.put("timeoutSeconds", timeoutSeconds);
        request.put("triggerSource", "GERRIT");
        request.put("externalRef", externalRef(task));
        request.put("idempotencyKey", "gerrit:" + task.getId() + ":" + task.getMergeCommitId() + ":" + request.get("environment"));
        if (callbackToken != null && !callbackToken.isBlank()) {
            request.put("callbackUrl", callbackBaseUrl + "/api/change-review/regression-callback");
        } else {
            log.warn("octopus.change-review.regression.callback-token 未配置：批次结果不会自动回写 Gerrit");
        }
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(scenarioUrl + "/api/business-testing/regression-batches"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ValidationException("scenario-runner 拒绝回归请求（HTTP " + response.statusCode() + "）: " + truncate(response.body(), 300));
            }
            return GSON.fromJson(response.body(), new TypeToken<Map<String, Object>>() {}.getType());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while contacting scenario-runner");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("scenario-runner 不可用: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 处理批次结束回调。
     *
     * @return true 表示已回写（或无需回写）；token 校验失败抛 {@link SecurityException}
     */
    public boolean onCallback(String token, Map<String, Object> summary) {
        if (callbackToken == null || callbackToken.isBlank()) {
            throw new IllegalStateException("regression callback is not configured");
        }
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), callbackToken.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("REGRESSION_CALLBACK_AUTH_FAILED");
        }
        Ref ref = parseRef(summary.get("externalRef"));
        if (ref == null) {
            log.info("Regression callback {} without Gerrit reference ignored", summary.get("id"));
            return true;
        }
        RepoConfig repoConfig = repoConfigs.findById(ref.repoConfigId())
                .orElseThrow(() -> new ResourceNotFoundException("Repo config not found: " + ref.repoConfigId()));
        String body = comments.build(summary, octopusBaseUrl);
        boolean ok = gerrit.postComment(repoConfig, ref.changeNumber(), body);
        if (!ok) log.warn("Failed to post regression result for change {}", ref.changeNumber());
        return ok;
    }

    record Ref(Long repoConfigId, String changeNumber) {}

    static String externalRef(ChangeReviewTask task) {
        return "gerrit:" + task.getRepoConfigId() + ":" + task.getChangeNumber() + ":" + task.getPatchsetNumber() + ":" + task.getId();
    }

    static Ref parseRef(Object raw) {
        if (!(raw instanceof String ref) || !ref.startsWith("gerrit:")) return null;
        String[] parts = ref.split(":");
        if (parts.length < 3 || !parts[1].matches("\\d{1,18}") || !parts[2].matches("[0-9A-Za-z~._-]{1,50}")) return null;
        return new Ref(Long.parseLong(parts[1]), parts[2]);
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
