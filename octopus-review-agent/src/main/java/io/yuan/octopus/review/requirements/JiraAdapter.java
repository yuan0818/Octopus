package io.yuan.octopus.review.requirements;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.yuan.octopus.review.dto.RequirementItem;
import io.yuan.octopus.review.entity.RequirementSourceConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Jira 适配器：单号 = Issue key（如 PROJ-123）。
 * <p>
 * GET {base}/rest/api/2/issue/{key}?fields=summary,description,issuetype,status,subtasks，
 * Basic 认证（email + API token，兼容 Cloud 与多数 Server）。主单与每个子任务各成一条需求。
 */
@Component
public class JiraAdapter implements RequirementSourceAdapter {

    @Override
    public String sourceType() {
        return "jira";
    }

    @Override
    public String displayName() {
        return "Jira";
    }

    @Override
    public List<RequirementItem> fetch(String ticketKey, RequirementSourceConfig config) {
        String base = trimSlash(config.getBaseUrl());
        String url = base + "/rest/api/2/issue/" + ticketKey
                + "?fields=summary,description,issuetype,status,subtasks";
        JsonObject root = SourceHttp.getJson(url, config.getAccount() + ":" + config.getSecret(), null);
        return parseTicket(root, ticketKey);
    }

    /** 包内可见：JSON → 需求条目（供单测使用固定 fixture）。 */
    List<RequirementItem> parseTicket(JsonObject root, String ticketKey) {
        List<RequirementItem> items = new ArrayList<>();
        JsonObject fields = root.getAsJsonObject("fields");
        if (fields == null) {
            throw new IllegalArgumentException("Jira 响应缺少 fields: " + ticketKey);
        }
        items.add(new RequirementItem(
                ticketKey,
                text(fields, "summary"),
                buildDescription(
                        text(fields, "description"),
                        nestedText(fields, "issuetype", "name"),
                        nestedText(fields, "status", "name"))));
        JsonArray subtasks = fields.getAsJsonArray("subtasks");
        if (subtasks != null) {
            for (JsonElement element : subtasks) {
                JsonObject subFields = element.getAsJsonObject().getAsJsonObject("fields");
                if (subFields == null) continue;
                String subKey = text(element.getAsJsonObject(), "key");
                items.add(new RequirementItem(
                        subKey,
                        text(subFields, "summary"),
                        buildDescription(text(subFields, "description"),
                                nestedText(subFields, "issuetype", "name"),
                                nestedText(subFields, "status", "name"))));
            }
        }
        return items;
    }

    private static String buildDescription(String description, String type, String status) {
        StringBuilder sb = new StringBuilder();
        if (type != null && !type.isBlank()) sb.append("[").append(type).append("] ");
        if (status != null && !status.isBlank()) sb.append("(").append(status).append(") ");
        sb.append(description != null && !description.isBlank() ? description : "（工单无描述）");
        return sb.toString();
    }

    private static String text(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && !e.isJsonNull() ? e.getAsString() : null;
    }

    private static String nestedText(JsonObject obj, String field, String member) {
        JsonElement e = obj.get(field);
        if (e == null || !e.isJsonObject()) return null;
        JsonElement m = e.getAsJsonObject().get(member);
        return m != null && !m.isJsonNull() ? m.getAsString() : null;
    }

    private static String trimSlash(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("Jira baseUrl 未配置，请先在 Agent 配置页填写需求源连接信息");
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
