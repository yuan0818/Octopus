package io.yuan.octopus.review.requirements;

import io.yuan.octopus.review.dto.RequirementItem;
import io.yuan.octopus.review.entity.RequirementSourceConfig;

import java.util.List;

/**
 * 需求源适配器 SPI：把外部系统的一个工单/文档拉取为结构化需求条目。
 * <p>
 * 审查主链路只消费 {@link RequirementItem}，完全不感知来源差异；
 * 新增来源（禅道、Azure DevOps…）实现本接口并注册为 Spring Bean 即可。
 */
public interface RequirementSourceAdapter {

    /** 来源标识（存配置、前端选择器使用），如 "jira" / "feishu" / "tapd"。 */
    String sourceType();

    /** 展示名，如 "Jira"。 */
    String displayName();

    /**
     * 拉取一个工单/文档的需求条目。
     *
     * @param ticketKey 工单号 / 文档链接或 token
     * @param config    该来源的连接配置（baseUrl/账号/密钥/扩展字段）
     * @return 结构化需求条目（至少一条）
     */
    List<RequirementItem> fetch(String ticketKey, RequirementSourceConfig config);

    /** 拉取结果（含工单元信息，供前端展示来源）。 */
    record FetchedTicket(String source, String ticketKey, String title, String url,
                         List<RequirementItem> items) {}
}
