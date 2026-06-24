package io.yuan.octopus.review.controller;

import io.yuan.octopus.infra.common.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LookupControllerTest {

    private JdbcTemplate jdbcTemplate;
    private LookupController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new LookupController(jdbcTemplate);
    }

    @Test
    void shouldListBusinessEntryClasses() {
        when(jdbcTemplate.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 1, "qualified_name", "com.example.CleanupJob", "simple_name", "CleanupJob", "class_type", "CLASS")
        ));

        Result<List<Map<String, Object>>> result = controller.listEntryClasses(9L, null);

        assertTrue(result.success());
        assertEquals("com.example.CleanupJob", result.data().get(0).get("qualified_name"));
        verify(jdbcTemplate).queryForList(any(String.class), any(Object[].class));
    }

    @Test
    void shouldListBusinessEntryMethods() {
        when(jdbcTemplate.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 2, "method_name", "cleanup", "method_signature", "com.example.CleanupJob.cleanup():void", "return_type", "void")
        ));

        Result<List<Map<String, Object>>> result = controller.listEntryMethods(2L, null);

        assertTrue(result.success());
        assertEquals("cleanup", result.data().get(0).get("method_name"));
        verify(jdbcTemplate).queryForList(any(String.class), any(Object[].class));
    }
}
