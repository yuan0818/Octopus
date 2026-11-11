package io.yuan.octopus.scenario.extension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** FR-FN-20/25: on-demand discovery with fixed budgets; tool results are data for the next round. */
class FunctionToolLoopTest {
    @Test
    void recognisesToolCallRoundsOnly() {
        assertNull(FunctionToolLoop.toolCalls("{\"node\":{\"id\":\"c1\"}}"));
        assertNull(FunctionToolLoop.toolCalls("not json"));
        var calls = FunctionToolLoop.toolCalls("```json\n{\"toolCalls\":[{\"name\":\"search_functions\",\"arguments\":{\"query\":\"签名\"}}]}\n```");
        assertEquals(1, calls.size());
        assertEquals("search_functions", calls.get(0).get("name"));
    }

    @Test
    void executesWithinBudgets() {
        var catalog = mock(FunctionCatalogService.class);
        when(catalog.search(anyString(), any(), anyInt())).thenReturn(List.of());
        when(catalog.describe(anyString())).thenThrow(new IllegalArgumentException("目录中没有 x"));
        var loop = new FunctionToolLoop(catalog);
        var round = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 5; i++) round.add(Map.of("name", "search_functions", "arguments", Map.of("query", "q" + i)));
        loop.execute(round);
        assertEquals(3, loop.calls(), "at most 3 calls per round");
        assertTrue(String.valueOf(loop.results().get(4).get("result")).contains("上限"));
        loop.execute(List.of(Map.of("name", "describe_function", "arguments", Map.of("name", "x@1")), Map.of("name", "rm_rf", "arguments", Map.of())));
        assertEquals("目录中没有 x", ((Map<?, ?>) loop.results().get(5).get("result")).get("error"));
        assertTrue(String.valueOf(loop.results().get(6).get("result")).contains("未知工具"));
        for (int i = 0; i < 5; i++) loop.execute(List.of());
        assertTrue(loop.exhausted(), "rounds are bounded even without calls");
    }
}
