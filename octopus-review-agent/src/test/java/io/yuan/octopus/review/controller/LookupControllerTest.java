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
import static org.mockito.ArgumentMatchers.anyString;
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

    // ── 版本化查询：listClasses 带 branch/commitId ────────────────

    @Test
    void shouldListClassesWithBranchAndCommitId() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 1, "qualified_name", "com.example.Controller", "simple_name", "Controller", "class_type", "CLASS")
        ));

        Result<List<Map<String, Object>>> result = controller.listClasses(9L, null, "main", "abc123");

        assertTrue(result.success());
        // 验证 JdbcTemplate 被调用，且参数包含 branch 和 commitId
        verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void shouldListClassesWithOnlyBranch() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 1, "qualified_name", "com.example.Controller", "simple_name", "Controller", "class_type", "CLASS")
        ));

        Result<List<Map<String, Object>>> result = controller.listClasses(9L, null, "feature/x", null);

        assertTrue(result.success());
        verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void shouldListClassesWithOnlyCommitId() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 1, "qualified_name", "com.example.Controller", "simple_name", "Controller", "class_type", "CLASS")
        ));

        Result<List<Map<String, Object>>> result = controller.listClasses(9L, null, null, "def456");

        assertTrue(result.success());
        verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void shouldListClassesWithSearchQueryAndVersion() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 1, "qualified_name", "com.example.OrderController", "simple_name", "OrderController", "class_type", "CLASS")
        ));

        Result<List<Map<String, Object>>> result = controller.listClasses(9L, "Order", "main", "abc123");

        assertTrue(result.success());
        verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
    }

    // ── 版本化查询：listMethods 带 branch/commitId ────────────────

    @Test
    void shouldListMethodsWithBranchAndCommitId() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 2, "method_name", "create", "method_signature", "com.example.Controller.create():void", "return_type", "void")
        ));

        Result<List<Map<String, Object>>> result = controller.listMethods(1L, null, "main", "abc123");

        assertTrue(result.success());
        assertEquals("create", result.data().get(0).get("method_name"));
        verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void shouldListMethodsWithOnlyBranch() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 2, "method_name", "create", "method_signature", "com.example.Controller.create():void", "return_type", "void")
        ));

        Result<List<Map<String, Object>>> result = controller.listMethods(1L, null, "feature/y", null);

        assertTrue(result.success());
        verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void shouldListMethodsWithOnlyCommitId() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 2, "method_name", "create", "method_signature", "com.example.Controller.create():void", "return_type", "void")
        ));

        Result<List<Map<String, Object>>> result = controller.listMethods(1L, null, null, "def456");

        assertTrue(result.success());
        verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void shouldListMethodsWithSearchQueryAndVersion() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 2, "method_name", "createOrder", "method_signature", "com.example.Controller.createOrder():void", "return_type", "void")
        ));

        Result<List<Map<String, Object>>> result = controller.listMethods(1L, "create", "main", "abc123");

        assertTrue(result.success());
        verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
    }
}
