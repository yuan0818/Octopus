package io.yuan.octopus.reconstruct.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Detects Spring Web API endpoints from MySQL annotation_info.
 * <p>
 * Given a set of class names, queries MySQL to find which methods
 * are annotated with {@code @RequestMapping}, {@code @GetMapping},
 * {@code @PostMapping}, etc., and extracts HTTP method + path.
 */
@Component
public class ApiEndpointDetector {

    private static final Logger log = LoggerFactory.getLogger(ApiEndpointDetector.class);

    private static final Set<String> ENDPOINT_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping"
    );

    private static final Map<String, String> ANNOTATION_TO_HTTP_METHOD = Map.of(
            "org.springframework.web.bind.annotation.GetMapping", "GET",
            "org.springframework.web.bind.annotation.PostMapping", "POST",
            "org.springframework.web.bind.annotation.PutMapping", "PUT",
            "org.springframework.web.bind.annotation.DeleteMapping", "DELETE",
            "org.springframework.web.bind.annotation.PatchMapping", "PATCH"
            // RequestMapping's method is in annotation_params
    );

    private final DataSource dataSource;

    public ApiEndpointDetector(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Detected API endpoint with metadata.
     */
    public record ApiEndpoint(
            String httpMethod,
            String path,
            String className,
            String methodName,
            String callType
    ) {}

    /**
     * Find all API endpoints among the given class names, scoped to a project.
     *
     * @param projectId  project ID for isolation
     * @param classNames set of fully-qualified class names to scan
     * @return list of detected endpoints
     */
    public List<ApiEndpoint> detect(Long projectId, Set<String> classNames) {
        if (classNames == null || classNames.isEmpty()) {
            return List.of();
        }
        try {
            return doDetect(projectId, classNames);
        } catch (SQLException e) {
            log.error("Failed to detect API endpoints: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ApiEndpoint> doDetect(Long projectId, Set<String> classNames) throws SQLException {
        // Build a parameterized IN clause with placeholders
        List<String> placeholders = classNames.stream().map(c -> "?").toList();
        String inClause = String.join(", ", placeholders);

        String sql = """
                SELECT ai.annotation_name, ai.annotation_params,
                       mi.method_name, mi.method_signature,
                       ci.qualified_name AS class_name
                FROM annotation_info ai
                JOIN method_info mi ON ai.target_id = mi.id AND ai.target_type = 'METHOD'
                JOIN class_info ci ON mi.class_id = ci.id
                WHERE ci.project_id = ?
                  AND ci.qualified_name IN (%s)
                  AND ai.annotation_name IN (%s)
                """.formatted(inClause, buildAnnotationPlaceholders());

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int idx = 1;
            ps.setLong(idx++, projectId);
            for (String cn : classNames) {
                ps.setString(idx++, cn);
            }
            for (String ann : ENDPOINT_ANNOTATIONS) {
                ps.setString(idx++, ann);
            }

            // Class-level annotations: find the class-level @RequestMapping and
            // associate it with each method-level endpoint
            Map<String, String> classLevelPaths = loadClassLevelPaths(projectId, classNames);

            List<ApiEndpoint> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String annotationName = rs.getString("annotation_name");
                    String annotationParams = rs.getString("annotation_params");
                    String methodName = rs.getString("method_name");
                    String className = rs.getString("class_name");

                    String httpMethod = resolveHttpMethod(annotationName, annotationParams);
                    String methodPath = resolvePath(annotationParams);
                    String classPath = classLevelPaths.getOrDefault(className, "");
                    String fullPath = joinPaths(classPath, methodPath);

                    results.add(new ApiEndpoint(
                            httpMethod,
                            fullPath,
                            className,
                            methodName,
                            annotationName.substring(annotationName.lastIndexOf('.') + 1)
                    ));
                }
            }
            log.debug("Detected {} endpoints in {} classes", results.size(), classNames.size());
            return results;
        }
    }

    /**
     * Load class-level @RequestMapping paths.
     */
    private Map<String, String> loadClassLevelPaths(Long projectId, Set<String> classNames) throws SQLException {
        List<String> placeholders = classNames.stream().map(c -> "?").toList();
        String inClause = String.join(", ", placeholders);

        String sql = """
                SELECT ci.qualified_name, ai.annotation_params
                FROM annotation_info ai
                JOIN class_info ci ON ai.target_id = ci.id AND ai.target_type = 'CLASS'
                WHERE ci.project_id = ?
                  AND ci.qualified_name IN (%s)
                  AND ai.annotation_name = 'org.springframework.web.bind.annotation.RequestMapping'
                """.formatted(inClause);

        Map<String, String> paths = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setLong(idx++, projectId);
            for (String cn : classNames) {
                ps.setString(idx++, cn);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String className = rs.getString("qualified_name");
                    String params = rs.getString("annotation_params");
                    String path = resolvePath(params);
                    if (path != null) {
                        paths.put(className, path);
                    }
                }
            }
        }
        return paths;
    }

    /**
     * Determines HTTP method from annotation name and params.
     */
    private String resolveHttpMethod(String annotationName, String annotationParams) {
        String explicit = ANNOTATION_TO_HTTP_METHOD.get(annotationName);
        if (explicit != null) {
            return explicit;
        }
        // @RequestMapping can specify method in params, e.g. {"method":"RequestMethod.POST"}
        if (annotationParams != null && annotationParams.contains("method")) {
            if (annotationParams.contains("POST")) return "POST";
            if (annotationParams.contains("PUT")) return "PUT";
            if (annotationParams.contains("DELETE")) return "DELETE";
            if (annotationParams.contains("PATCH")) return "PATCH";
            if (annotationParams.contains("GET")) return "GET";
        }
        return "GET"; // default for @RequestMapping without explicit method
    }

    /**
     * Extracts the path value from annotation_params JSON-like string.
     * Example: {"value":"\"/api/users\""} → /api/users
     */
    private String resolvePath(String annotationParams) {
        if (annotationParams == null || annotationParams.isBlank()) {
            return "";
        }
        // Try to extract "value" or "path" key from the JSON-like string
        String cleaned = annotationParams
                .replaceAll("\\\\\"", "\"")
                .replaceAll("^\\{|\\}$", "")
                .trim();

        // Try value=... or path=...
        String path = extractQuotedValue(cleaned, "\"value\"");
        if (path == null || path.isBlank()) {
            path = extractQuotedValue(cleaned, "\"path\"");
        }
        // Also try without quotes (Kotlin-style)
        if (path == null || path.isBlank()) {
            path = extractSimpleValue(cleaned, "value=");
        }
        if (path == null || path.isBlank()) {
            path = extractSimpleValue(cleaned, "path=");
        }
        return path != null ? path.trim() : "";
    }

    private String extractQuotedValue(String cleaned, String key) {
        int keyIdx = cleaned.indexOf(key);
        if (keyIdx < 0) return null;
        int colonIdx = cleaned.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return null;
        String afterColon = cleaned.substring(colonIdx + 1).trim();
        if (afterColon.startsWith("\"")) {
            int endQuote = afterColon.indexOf('"', 1);
            if (endQuote > 1) {
                return afterColon.substring(1, endQuote);
            }
        }
        return null;
    }

    private String extractSimpleValue(String cleaned, String key) {
        int keyIdx = cleaned.indexOf(key);
        if (keyIdx < 0) return null;
        String after = cleaned.substring(keyIdx + key.length()).trim();
        if (after.startsWith("\"")) {
            int end = after.indexOf('"', 1);
            if (end > 1) return after.substring(1, end);
        }
        // non-quoted value until comma or end
        int comma = after.indexOf(',');
        return comma > 0 ? after.substring(0, comma).trim() : after.trim();
    }

    private String joinPaths(String classPath, String methodPath) {
        StringBuilder sb = new StringBuilder();
        if (classPath != null && !classPath.isBlank()) {
            sb.append(classPath.startsWith("/") ? "" : "/").append(classPath);
        }
        if (methodPath != null && !methodPath.isBlank()) {
            sb.append(methodPath.startsWith("/") ? "" : "/").append(methodPath);
        }
        String result = sb.toString();
        return result.isEmpty() ? "/" : result;
    }

    private String buildAnnotationPlaceholders() {
        return ENDPOINT_ANNOTATIONS.stream()
                .map(a -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
