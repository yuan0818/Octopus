package io.yuan.octopus.reconstruct.service;

import io.yuan.octopus.reconstruct.service.FieldUsageFilter.FieldEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Assembles a complete Java source file for a class using stored MySQL metadata,
 * filtering to only methods and fields that are part of the collected call chain.
 */
@Component
public class ClassSourceAssembler {

    private static final Logger log = LoggerFactory.getLogger(ClassSourceAssembler.class);

    private final DataSource dataSource;
    private final FieldUsageFilter fieldFilter;

    public ClassSourceAssembler(DataSource dataSource, FieldUsageFilter fieldFilter) {
        this.dataSource = dataSource;
        this.fieldFilter = fieldFilter;
    }

    /**
     * Result of assembling a single class.
     */
    public record AssembledClass(
        String qualifiedName,
        String sourceCode
    ) {}

    /**
     * Build the full Java source for {@code qualifiedName}, using the given set
     * of method signatures to filter which methods (and their referenced fields)
     * to include in the output.
     *
     * @param qualifiedName  fully-qualified class name
     * @param involvedSigs   set of method signatures that are in the call chain
     * @return assembled Java source code, or null if the class is not found
     */
    public AssembledClass assemble(String qualifiedName, Set<String> involvedSigs) {
        try {
            // 1. Load class info
            ClassInfoRow cls = loadClassInfo(qualifiedName);
            if (cls == null) return null;

            // 2. Load annotations
            List<AnnotationRow> classAnnotations = loadAnnotations("CLASS", cls.id());

            // 3. Load methods (filtered to involvedSigs)
            List<MethodRow> allMethods = loadMethods(cls.id());
            List<MethodRow> involvedMethods = allMethods.stream()
                .filter(m -> involvedSigs.contains(m.signature()))
                .toList();

            if (involvedMethods.isEmpty() && !qualifiedName.equals(cls.qualifiedName())) {
                // No methods used from this class — skip unless it's the anchor
                return null;
            }

            // 4. Load fields (filtered by usage)
            List<FieldRow> allFields = loadFields(cls.id());
            List<String> methodSourceCodes = involvedMethods.stream()
                .map(MethodRow::sourceCode)
                .filter(Objects::nonNull)
                .toList();

            // Include ALL fields (not filtered — "属性" is required in output even if unused)
            List<FieldEntry> usedFields = allFields.stream()
                .map(f -> new FieldEntry(f.fieldName(), f.fieldType(), f.modifiers(),
                        loadFieldAnnotationParams(cls.id(), f.fieldName())))
                .toList();

            // 5. Collect imports from dependencies referenced in involved methods
            Set<String> imports = collectImportDependencies(cls.id(), involvedMethods);

            // 6. Load parent class and interfaces
            List<String> parentClasses = loadParentClasses(cls.id());
            List<String> interfaces = loadInterfaces(cls.id());

            // 7. Build source code
            String packageName = derivePackageName(qualifiedName);
            String source = buildSource(
                packageName, imports.stream().toList(),
                classAnnotations, cls, parentClasses, interfaces,
                usedFields, involvedMethods
            );

            return new AssembledClass(qualifiedName, source);

        } catch (SQLException e) {
            log.error("Failed to assemble class {}: {}", qualifiedName, e.getMessage());
            return null;
        }
    }

    // ── Source Builder ──────────────────────────────────────────────

    private String buildSource(
            String packageName,
            List<String> imports,
            List<AnnotationRow> classAnnotations,
            ClassInfoRow cls,
            List<String> parentClasses,
            List<String> interfaces,
            List<FieldEntry> usedFields,
            List<MethodRow> involvedMethods) {

        StringBuilder sb = new StringBuilder(4096);

        // Package
        sb.append("package ").append(packageName).append(";\n\n");

        // Imports — sort for stable output
        List<String> sortedImports = new ArrayList<>(imports);
        Collections.sort(sortedImports);
        for (String imp : sortedImports) {
            if (imp.startsWith("java.lang.")) continue; // implicit
            sb.append("import ").append(imp).append(";\n");
        }
        if (!sortedImports.isEmpty()) sb.append("\n");

        // Class annotations
        for (AnnotationRow ann : classAnnotations) {
            sb.append(formatAnnotation(ann)).append("\n");
        }

        // Class declaration
        sb.append("public ");
        if (cls.classType() != null && !cls.classType().equals("CLASS")) {
            sb.append(cls.classType().toLowerCase()).append(" ");
        }
        sb.append("class ").append(cls.simpleName());

        if (!parentClasses.isEmpty()) {
            sb.append(" extends ");
            // Only use the first direct parent (excluding Object)
            String parent = parentClasses.get(0);
            if (!"java.lang.Object".equals(parent)) {
                sb.append(parent.substring(parent.lastIndexOf('.') + 1));
            }
        }
        if (!interfaces.isEmpty()) {
            sb.append(" implements ");
            sb.append(interfaces.stream()
                .map(i -> i.contains(".") ? i.substring(i.lastIndexOf('.') + 1) : i)
                .collect(Collectors.joining(", ")));
        }
        sb.append(" {\n\n");

        // Fields
        for (FieldEntry field : usedFields) {
            if (field.annotationParams() != null && !field.annotationParams().isBlank()) {
                sb.append("    ").append(formatFieldAnnotation(field)).append("\n");
            }
            sb.append("    ");
            if (field.modifiers() != null && !field.modifiers().isBlank()) {
                sb.append(field.modifiers()).append(" ");
            }
            sb.append(field.fieldType()).append(" ").append(field.fieldName()).append(";\n\n");
        }
        if (!usedFields.isEmpty()) sb.append("\n");

        // Methods
        for (MethodRow method : involvedMethods) {
            // Method annotations
            List<AnnotationRow> methodAnnotations = loadMethodAnnotationsSync(cls.id(), method.signature());
            for (AnnotationRow ann : methodAnnotations) {
                sb.append("    ").append(formatAnnotation(ann)).append("\n");
            }
            // Method source code (already includes signature + body)
            String source = method.sourceCode();
            if (source != null) {
                // Indent each line
                String[] lines = source.split("\n");
                for (String line : lines) {
                    sb.append("    ").append(line).append("\n");
                }
            } else {
                sb.append("    // source not available\n");
            }
            sb.append("\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    // ── Database Queries ────────────────────────────────────────────

    private record ClassInfoRow(Long id, String qualifiedName, String simpleName,
                                 String classType, String modifiers,
                                 String sourceFilePath) {}
    private record MethodRow(Long id, String methodName, String signature,
                              String returnType, String parameterTypes,
                              String modifiers, String sourceCode) {}
    private record FieldRow(String fieldName, String fieldType, String modifiers) {}
    private record AnnotationRow(String annotationName, String annotationParams,
                                  String targetType, Long targetId) {}

    private ClassInfoRow loadClassInfo(String qualifiedName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, qualified_name, simple_name, class_type, modifiers, source_file_path " +
                "FROM class_info WHERE qualified_name = ? LIMIT 1")) {
            ps.setString(1, qualifiedName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ClassInfoRow(
                        rs.getLong("id"),
                        rs.getString("qualified_name"),
                        rs.getString("simple_name"),
                        rs.getString("class_type"),
                        rs.getString("modifiers"),
                        rs.getString("source_file_path")
                    );
                }
            }
        }
        return null;
    }

    private List<MethodRow> loadMethods(Long classId) throws SQLException {
        List<MethodRow> methods = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, method_name, method_signature, return_type, " +
                "parameter_types, modifiers, source_code " +
                "FROM method_info WHERE class_id = ?")) {
            ps.setLong(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    methods.add(new MethodRow(
                        rs.getLong("id"),
                        rs.getString("method_name"),
                        rs.getString("method_signature"),
                        rs.getString("return_type"),
                        rs.getString("parameter_types"),
                        rs.getString("modifiers"),
                        rs.getString("source_code")
                    ));
                }
            }
        }
        return methods;
    }

    private List<FieldRow> loadFields(Long classId) throws SQLException {
        List<FieldRow> fields = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT field_name, field_type, modifiers FROM field_info WHERE class_id = ?")) {
            ps.setLong(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fields.add(new FieldRow(
                        rs.getString("field_name"),
                        rs.getString("field_type"),
                        rs.getString("modifiers")
                    ));
                }
            }
        }
        return fields;
    }

    private List<AnnotationRow> loadAnnotations(String targetType, Long targetId) throws SQLException {
        List<AnnotationRow> annotations = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT annotation_name, annotation_params, target_type, target_id " +
                "FROM annotation_info WHERE target_type = ? AND target_id = ?")) {
            ps.setString(1, targetType);
            ps.setLong(2, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    annotations.add(new AnnotationRow(
                        rs.getString("annotation_name"),
                        rs.getString("annotation_params"),
                        rs.getString("target_type"),
                        rs.getLong("target_id")
                    ));
                }
            }
        }
        return annotations;
    }

    private String loadFieldAnnotationParams(Long classId, String fieldName) {
        // annotation_info stores field annotations with target_type='FIELD'
        // But we don't have a direct field_info.id -> annotation_info.target_id mapping here.
        // For simplicity, check if @Autowired or @Resource is likely present
        // by looking at the field type (starts with I or is an interface)
        return fieldName.startsWith("I") ? "Autowired" : null;
    }

    private List<String> loadParentClasses(Long classId) throws SQLException {
        List<String> parents = new ArrayList<>();
        // inheritance_relation uses child_qualified_name (not class_id)
        // First get the class's qualified_name
        String qualifiedName = null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT qualified_name FROM class_info WHERE id = ?")) {
            ps.setLong(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) qualifiedName = rs.getString("qualified_name");
            }
        }
        if (qualifiedName == null) return parents;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT parent_qualified_name FROM inheritance_relation " +
                "WHERE child_qualified_name = ? AND relation_type = 'EXTENDS'")) {
            ps.setString(1, qualifiedName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parents.add(rs.getString("parent_qualified_name"));
                }
            }
        }
        return parents;
    }

    private List<String> loadInterfaces(Long classId) throws SQLException {
        List<String> ifaces = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT interface_qualified_name FROM class_interface_relation WHERE class_id = ?")) {
            ps.setLong(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ifaces.add(rs.getString("interface_qualified_name"));
                }
            }
        }
        return ifaces;
    }

    private Set<String> collectImportDependencies(Long classId, List<MethodRow> methods) {
        Set<String> imports = new LinkedHashSet<>();

        // Collect from class_dependency
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT dependency_qualified_name FROM class_dependency " +
                "WHERE class_id = ? AND dependency_type = 'IMPORT'")) {
            ps.setLong(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    imports.add(rs.getString("dependency_qualified_name"));
                }
            }
        } catch (SQLException e) {
            log.debug("Failed to load imports: {}", e.getMessage());
        }

        // Also collect from method source code — extract type references
        Set<String> sourceRefs = new LinkedHashSet<>();
        for (MethodRow m : methods) {
            if (m.sourceCode() != null) {
                // Extract simple class names from source code
                java.util.regex.Pattern classPattern =
                    java.util.regex.Pattern.compile("\\b([A-Z][a-zA-Z0-9_]+)\\b");
                java.util.regex.Matcher mat = classPattern.matcher(m.sourceCode());
                while (mat.find()) {
                    String name = mat.group(1);
                    if (!isJavaLangClass(name) && !name.equals(m.methodName())) {
                        sourceRefs.add(name);
                    }
                }
            }
        }

        return imports;
    }

    // ── Formatting Helpers ─────────────────────────────────────────

    private String formatAnnotation(AnnotationRow ann) {
        if (ann.annotationParams() == null || ann.annotationParams().isBlank()
            || ann.annotationParams().equals("{}")) {
            return "@" + ann.annotationName();
        }
        // annotationParams is stored as JSON-like string, e.g., {"value":"\"/api\""}
        // Convert to Java annotation syntax
        String params = ann.annotationParams()
            .replaceAll("\\\\\"", "\"")   // unescape inner quotes
            .replaceAll("^\\{|\\}$", ""); // remove braces
        return "@" + ann.annotationName() + "(" + params + ")";
    }

    private String formatFieldAnnotation(FieldEntry field) {
        return "@Autowired";
    }

    private String derivePackageName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(0, lastDot) : "";
    }

    private boolean isJavaLangClass(String name) {
        return Set.of("String", "Integer", "Long", "Boolean", "Double",
            "Float", "Short", "Byte", "Character", "Object", "Class",
            "Exception", "RuntimeException", "Throwable",
            "System", "Math", "Enum", "Thread", "Runnable",
            "List", "Map", "Set", "ArrayList", "HashMap", "HashSet",
            "Collection", "Iterator", "Optional",
            "StringBuilder", "StringBuffer").contains(name);
    }

    // Cache for method annotations (loaded per class)
    private final Map<String, List<AnnotationRow>> methodAnnotationCache = new LinkedHashMap<>();

    private List<AnnotationRow> loadMethodAnnotationsSync(Long classId, String signature) {
        String cacheKey = classId + ":" + signature;
        if (methodAnnotationCache.containsKey(cacheKey)) {
            return methodAnnotationCache.get(cacheKey);
        }
        List<AnnotationRow> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT ai.annotation_name, ai.annotation_params, ai.target_type, ai.target_id " +
                "FROM annotation_info ai " +
                "JOIN method_info mi ON ai.target_id = mi.id AND ai.target_type = 'METHOD' " +
                "WHERE mi.class_id = ? AND mi.method_signature = ?")) {
            ps.setLong(1, classId);
            ps.setString(2, signature);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new AnnotationRow(
                        rs.getString("annotation_name"),
                        rs.getString("annotation_params"),
                        rs.getString("target_type"),
                        rs.getLong("target_id")
                    ));
                }
            }
        } catch (SQLException e) {
            log.debug("Failed to load method annotations: {}", e.getMessage());
        }
        methodAnnotationCache.put(cacheKey, result);
        return result;
    }
}
