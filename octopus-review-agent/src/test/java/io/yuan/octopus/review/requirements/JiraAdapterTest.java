package io.yuan.octopus.review.requirements;

import com.google.gson.JsonParser;
import io.yuan.octopus.review.dto.RequirementItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JiraAdapterTest {

    private final JiraAdapter adapter = new JiraAdapter();

    @Test
    void shouldParseMainTicketAndSubtasks() {
        String json = """
                {
                  "key": "PROJ-123",
                  "fields": {
                    "summary": "用户注销后清理会话",
                    "description": "注销接口需要清理 Redis 会话并下发登出事件",
                    "issuetype": {"name": "Story"},
                    "status": {"name": "In Progress"},
                    "subtasks": [
                      {
                        "key": "PROJ-124",
                        "fields": {
                          "summary": "清理 Redis 会话",
                          "description": "del session:{userId}",
                          "issuetype": {"name": "Sub-task"},
                          "status": {"name": "To Do"}
                        }
                      },
                      {
                        "key": "PROJ-125",
                        "fields": {
                          "summary": "下发登出事件",
                          "description": null,
                          "issuetype": {"name": "Sub-task"},
                          "status": {"name": "To Do"}
                        }
                      }
                    ]
                  }
                }
                """;

        List<RequirementItem> items = adapter.parseTicket(
                JsonParser.parseString(json).getAsJsonObject(), "PROJ-123");

        assertEquals(3, items.size());

        RequirementItem main = items.get(0);
        assertEquals("PROJ-123", main.getId());
        assertEquals("用户注销后清理会话", main.getTitle());
        assertTrue(main.getDescription().contains("[Story]"));
        assertTrue(main.getDescription().contains("(In Progress)"));
        assertTrue(main.getDescription().contains("Redis 会话"));

        assertEquals("PROJ-124", items.get(1).getId());
        assertTrue(items.get(1).getDescription().contains("del session:{userId}"));

        // 无描述的子任务回退到占位说明而不是 null
        assertEquals("PROJ-125", items.get(2).getId());
        assertNotNull(items.get(2).getDescription());
    }

    @Test
    void shouldRejectResponseWithoutFields() {
        assertThrows(IllegalArgumentException.class, () ->
                adapter.parseTicket(JsonParser.parseString("{}").getAsJsonObject(), "X-1"));
    }
}
