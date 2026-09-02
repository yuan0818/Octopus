package io.yuan.octopus.review.requirements;

import io.yuan.octopus.review.dto.RequirementItem;
import io.yuan.octopus.review.entity.RequirementSourceConfig;
import io.yuan.octopus.review.repository.RequirementSourceConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求源服务：按来源标识路由到注册的 {@link RequirementSourceAdapter}。
 * <p>
 * 扩展方式：实现 {@link RequirementSourceAdapter} 并注册为 Spring Bean，
 * 即自动出现在源列表和拉取路由中（当前内置 Jira；飞书/TAPD 等由使用者按需实现）。
 */
@Service
public class RequirementSourceService {

    private static final Logger log = LoggerFactory.getLogger(RequirementSourceService.class);

    private final List<RequirementSourceAdapter> adapters;
    private final RequirementSourceConfigRepository configRepository;

    public RequirementSourceService(List<RequirementSourceAdapter> adapters,
                                    RequirementSourceConfigRepository configRepository) {
        this.adapters = adapters;
        this.configRepository = configRepository;
        log.info("Registered {} requirement source adapter(s): {}",
                adapters.size(), adapters.stream().map(RequirementSourceAdapter::sourceType).toList());
    }

    /** 已注册的源及其配置状态（不返回密钥）。 */
    public List<Map<String, Object>> listSources() {
        return adapters.stream().map(adapter -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceType", adapter.sourceType());
            row.put("displayName", adapter.displayName());
            configRepository.findBySourceType(adapter.sourceType()).ifPresentOrElse(
                    config -> {
                        row.put("configured", config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                                && config.getAccount() != null && !config.getAccount().isBlank()
                                && config.getSecret() != null && !config.getSecret().isBlank());
                        row.put("baseUrl", config.getBaseUrl());
                        row.put("account", config.getAccount());
                    },
                    () -> row.put("configured", false));
            return row;
        }).toList();
    }

    /** 拉取一个工单的需求条目。 */
    public RequirementSourceAdapter.FetchedTicket fetch(String sourceType, String ticketKey) {
        RequirementSourceAdapter adapter = adapters.stream()
                .filter(a -> a.sourceType().equalsIgnoreCase(sourceType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知需求源: " + sourceType + "（已注册: "
                                + adapters.stream().map(RequirementSourceAdapter::sourceType).toList() + "）"));
        RequirementSourceConfig config = configRepository.findBySourceType(adapter.sourceType())
                .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "需求源 '" + adapter.displayName() + "' 未配置，请先在 Agent 配置页填写连接信息"));

        List<RequirementItem> items = adapter.fetch(ticketKey, config);
        log.info("Fetched {} requirement item(s) from {} ticket {}", items.size(), sourceType, ticketKey);
        return new RequirementSourceAdapter.FetchedTicket(
                adapter.sourceType(), ticketKey,
                items.isEmpty() ? ticketKey : items.get(0).getTitle(),
                buildUrl(adapter, config, ticketKey),
                items);
    }

    /** 连通性测试：拉取动作本身即测试（单号可为空时退化为配置完整性检查）。 */
    public String test(String sourceType) {
        List<Map<String, Object>> sources = listSources();
        return sources.stream()
                .filter(s -> sourceType.equalsIgnoreCase((String) s.get("sourceType")))
                .findFirst()
                .map(s -> Boolean.TRUE.equals(s.get("configured")) ? "配置完整" : "配置不完整：缺少 baseUrl/账号/密钥")
                .orElse("未知需求源: " + sourceType);
    }

    private static String buildUrl(RequirementSourceAdapter adapter, RequirementSourceConfig config, String ticketKey) {
        if (adapter instanceof JiraAdapter && config.getBaseUrl() != null) {
            String base = config.getBaseUrl().endsWith("/")
                    ? config.getBaseUrl().substring(0, config.getBaseUrl().length() - 1) : config.getBaseUrl();
            return base + "/browse/" + ticketKey;
        }
        return ticketKey;
    }
}
