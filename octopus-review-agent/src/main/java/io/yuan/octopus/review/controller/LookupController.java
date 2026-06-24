package io.yuan.octopus.review.controller;

import io.yuan.octopus.infra.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Provides lookup data for the frontend: projects, classes, methods.
 * Eliminates manual entry of project IDs, class names, and method names.
 */
@RestController
@RequestMapping("/api/lookup")
public class LookupController {

    private static final String HTTP_ENTRY_ANNOTATION_SQL = "'GetMapping','PostMapping','PutMapping','DeleteMapping','PatchMapping','RequestMapping'";
    private static final String BUSINESS_ENTRY_ANNOTATION_SQL = HTTP_ENTRY_ANNOTATION_SQL
            + ",'Scheduled','EventListener','KafkaListener','RabbitListener','JmsListener','SqsListener','RocketMQMessageListener'";

    private final JdbcTemplate jdbc;

    public LookupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** List all projects with their IDs and names. */
    @GetMapping("/projects")
    public Result<List<Map<String, Object>>> listProjects() {
        List<Map<String, Object>> projects = jdbc.queryForList(
                "SELECT id, project_name, branch, commit_id, is_api_service, is_backend_project, analyzed_at " +
                "FROM java_project ORDER BY analyzed_at DESC LIMIT 50");
        return Result.success(projects);
    }

    /** List classes in a project (full-text search or prefix match). */
    @GetMapping("/classes")
    public Result<List<Map<String, Object>>> listClasses(
            @RequestParam Long projectId,
            @RequestParam(required = false) String q) {
        String sql;
        Object[] params;
        if (q != null && !q.isBlank()) {
            sql = "SELECT ci.id, ci.qualified_name, ci.simple_name, ci.class_type " +
                  "FROM class_info ci WHERE ci.project_id = ? AND ci.qualified_name LIKE ? " +
                  "ORDER BY ci.qualified_name LIMIT 100";
            params = new Object[]{projectId, "%" + q + "%"};
        } else {
            sql = "SELECT ci.id, ci.qualified_name, ci.simple_name, ci.class_type " +
                  "FROM class_info ci WHERE ci.project_id = ? " +
                  "ORDER BY ci.qualified_name LIMIT 200";
            params = new Object[]{projectId};
        }
        return Result.success(jdbc.queryForList(sql, params));
    }

    /** List methods in a class. */
    @GetMapping("/methods")
    public Result<List<Map<String, Object>>> listMethods(
            @RequestParam Long classId,
            @RequestParam(required = false) String q) {
        String sql;
        Object[] params;
        if (q != null && !q.isBlank()) {
            sql = "SELECT mi.id, mi.method_name, mi.method_signature, mi.return_type " +
                  "FROM method_info mi WHERE mi.class_id = ? AND mi.method_name LIKE ? " +
                  "ORDER BY mi.method_name LIMIT 100";
            params = new Object[]{classId, "%" + q + "%"};
        } else {
            sql = "SELECT mi.id, mi.method_name, mi.method_signature, mi.return_type " +
                  "FROM method_info mi WHERE mi.class_id = ? " +
                  "ORDER BY mi.method_name LIMIT 200";
            params = new Object[]{classId};
        }
        return Result.success(jdbc.queryForList(sql, params));
    }

    /**
     * List API endpoint classes (classes annotated with @RestController or @Controller)
     * for a project, optionally limited to those with a given annotation.
     */
    @GetMapping("/endpoint-classes")
    public Result<List<Map<String, Object>>> listEndpointClasses(
            @RequestParam Long projectId,
            @RequestParam(required = false) String q) {
        String sql;
        Object[] params;
        if (q != null && !q.isBlank()) {
            sql = "SELECT DISTINCT ci.id, ci.qualified_name, ci.simple_name, ci.class_type " +
                  "FROM class_info ci " +
                  "JOIN annotation_info ai ON ai.target_id = ci.id AND ai.target_type = 'CLASS' " +
                  "WHERE ci.project_id = ? AND ai.annotation_name IN ('RestController','Controller') " +
                  "AND ci.qualified_name LIKE ? " +
                  "ORDER BY ci.qualified_name LIMIT 100";
            params = new Object[]{projectId, "%" + q + "%"};
        } else {
            sql = "SELECT DISTINCT ci.id, ci.qualified_name, ci.simple_name, ci.class_type " +
                  "FROM class_info ci " +
                  "JOIN annotation_info ai ON ai.target_id = ci.id AND ai.target_type = 'CLASS' " +
                  "WHERE ci.project_id = ? AND ai.annotation_name IN ('RestController','Controller') " +
                  "ORDER BY ci.qualified_name LIMIT 200";
            params = new Object[]{projectId};
        }
        return Result.success(jdbc.queryForList(sql, params));
    }

    /**
     * List endpoint methods (methods annotated with @XxxMapping) in a class.
     */
    @GetMapping("/endpoint-methods")
    public Result<List<Map<String, Object>>> listEndpointMethods(
            @RequestParam Long classId,
            @RequestParam(required = false) String q) {
        String sql;
        Object[] params;
        if (q != null && !q.isBlank()) {
            sql = "SELECT DISTINCT mi.id, mi.method_name, mi.method_signature, mi.return_type " +
                  "FROM method_info mi " +
                  "JOIN annotation_info ai ON ai.target_id = mi.id AND ai.target_type = 'METHOD' " +
                  "WHERE mi.class_id = ? AND ai.annotation_name IN ('GetMapping','PostMapping','PutMapping','DeleteMapping','RequestMapping') " +
                  "AND mi.method_name LIKE ? " +
                  "ORDER BY mi.method_name LIMIT 100";
            params = new Object[]{classId, "%" + q + "%"};
        } else {
            sql = "SELECT DISTINCT mi.id, mi.method_name, mi.method_signature, mi.return_type " +
                  "FROM method_info mi " +
                  "JOIN annotation_info ai ON ai.target_id = mi.id AND ai.target_type = 'METHOD' " +
                  "WHERE mi.class_id = ? AND ai.annotation_name IN ('GetMapping','PostMapping','PutMapping','DeleteMapping','RequestMapping') " +
                  "ORDER BY mi.method_name LIMIT 200";
            params = new Object[]{classId};
        }
        return Result.success(jdbc.queryForList(sql, params));
    }

    /**
     * Lists business-entry classes, prioritizing HTTP API entry owners while also including
     * scheduled jobs and listener-based entry methods.
     */
    @GetMapping("/entry-classes")
    public Result<List<Map<String, Object>>> listEntryClasses(
            @RequestParam Long projectId,
            @RequestParam(required = false) String q) {
        String sql;
        Object[] params;
        if (q != null && !q.isBlank()) {
            sql = "SELECT DISTINCT ci.id, ci.qualified_name, ci.simple_name, ci.class_type " +
                  "FROM class_info ci " +
                  "JOIN method_info mi ON mi.class_id = ci.id " +
                  "JOIN annotation_info ai ON ai.target_id = mi.id AND ai.target_type = 'METHOD' " +
                  "WHERE ci.project_id = ? AND ai.annotation_name IN (" + BUSINESS_ENTRY_ANNOTATION_SQL + ") " +
                  "AND ci.qualified_name LIKE ? " +
                  "ORDER BY ci.qualified_name LIMIT 100";
            params = new Object[]{projectId, "%" + q + "%"};
        } else {
            sql = "SELECT DISTINCT ci.id, ci.qualified_name, ci.simple_name, ci.class_type " +
                  "FROM class_info ci " +
                  "JOIN method_info mi ON mi.class_id = ci.id " +
                  "JOIN annotation_info ai ON ai.target_id = mi.id AND ai.target_type = 'METHOD' " +
                  "WHERE ci.project_id = ? AND ai.annotation_name IN (" + BUSINESS_ENTRY_ANNOTATION_SQL + ") " +
                  "ORDER BY ci.qualified_name LIMIT 200";
            params = new Object[]{projectId};
        }
        return Result.success(jdbc.queryForList(sql, params));
    }

    /**
     * Lists business-entry methods in a class.
     */
    @GetMapping("/entry-methods")
    public Result<List<Map<String, Object>>> listEntryMethods(
            @RequestParam Long classId,
            @RequestParam(required = false) String q) {
        String sql;
        Object[] params;
        if (q != null && !q.isBlank()) {
            sql = "SELECT DISTINCT mi.id, mi.method_name, mi.method_signature, mi.return_type " +
                  "FROM method_info mi " +
                  "JOIN annotation_info ai ON ai.target_id = mi.id AND ai.target_type = 'METHOD' " +
                  "WHERE mi.class_id = ? AND ai.annotation_name IN (" + BUSINESS_ENTRY_ANNOTATION_SQL + ") " +
                  "AND mi.method_name LIKE ? " +
                  "ORDER BY mi.method_name LIMIT 100";
            params = new Object[]{classId, "%" + q + "%"};
        } else {
            sql = "SELECT DISTINCT mi.id, mi.method_name, mi.method_signature, mi.return_type " +
                  "FROM method_info mi " +
                  "JOIN annotation_info ai ON ai.target_id = mi.id AND ai.target_type = 'METHOD' " +
                  "WHERE mi.class_id = ? AND ai.annotation_name IN (" + BUSINESS_ENTRY_ANNOTATION_SQL + ") " +
                  "ORDER BY mi.method_name LIMIT 200";
            params = new Object[]{classId};
        }
        return Result.success(jdbc.queryForList(sql, params));
    }

    /** Quick class search across all projects. */
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> searchClasses(
            @RequestParam String q,
            @RequestParam(required = false) Long projectId) {
        String sql;
        Object[] params;
        if (projectId != null) {
            sql = "SELECT ci.id AS class_id, ci.qualified_name, ci.simple_name, " +
                  "ci.class_type, jp.project_name, jp.id AS project_id " +
                  "FROM class_info ci JOIN java_project jp ON ci.project_id = jp.id " +
                  "WHERE ci.project_id = ? AND ci.qualified_name LIKE ? " +
                  "ORDER BY ci.qualified_name LIMIT 50";
            params = new Object[]{projectId, "%" + q + "%"};
        } else {
            sql = "SELECT ci.id AS class_id, ci.qualified_name, ci.simple_name, " +
                  "ci.class_type, jp.project_name, jp.id AS project_id " +
                  "FROM class_info ci JOIN java_project jp ON ci.project_id = jp.id " +
                  "WHERE ci.qualified_name LIKE ? " +
                  "ORDER BY ci.project_id, ci.qualified_name LIMIT 50";
            params = new Object[]{"%" + q + "%"};
        }
        return Result.success(jdbc.queryForList(sql, params));
    }
}
