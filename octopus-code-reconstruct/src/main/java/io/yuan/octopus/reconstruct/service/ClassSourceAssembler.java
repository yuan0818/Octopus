package io.yuan.octopus.reconstruct.service;

import io.yuan.octopus.reconstruct.service.FieldUsageFilter.FieldEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

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
    public AssembledClass assemble(Long projectId, String qualifiedName, Set<String> involvedSigs) {
        try {
            // 1. Load class info (scoped to project)
            ClassInfoRow cls = loadClassInfo(projectId, qualifiedName);
            if (cls == null) return null;

            // 2. Load annotations
            List<AnnotationRow> classAnnotations = loadAnnotations("CLASS", cls.id());

            // 3. Load methods (filtered to involvedSigs; null = include all)
            List<MethodRow> allMethods = loadMethods(cls.id());
            List<MethodRow> involvedMethods = involvedSigs != null
                ? allMethods.stream().filter(m -> signatureMatch(m.signature(), involvedSigs)).toList()
                : allMethods;

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

            // 5. Collect imports from dependencies referenced in methods + fields.
            //    Use allMethods when involved is empty (anchor class signature mismatch).
            List<MethodRow> importMethods = involvedMethods.isEmpty() ? allMethods : involvedMethods;
            String preferredPackage = cls.qualifiedName().contains(".")
                ? cls.qualifiedName().substring(0, cls.qualifiedName().lastIndexOf('.'))
                : "";
            Set<String> imports = collectImportDependencies(cls.id(), importMethods, usedFields, projectId, preferredPackage);

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
        String ct = cls.classType() != null ? cls.classType() : "CLASS";
        sb.append("public ");
        if (!"CLASS".equals(ct)) {
            sb.append(ct.toLowerCase()).append(" ");
        } else {
            sb.append("class ");
        }
        sb.append(cls.simpleName());
        // For interfaces: "implements" doesn't make sense — use "extends"
        // For classes: use "extends"
        // For enums/records: handled above

        if (!parentClasses.isEmpty()) {
            sb.append(" extends ");
            // Only use the first direct parent (excluding Object)
            String parent = parentClasses.get(0);
            if (!"java.lang.Object".equals(parent)) {
                sb.append(parent.substring(parent.lastIndexOf('.') + 1));
            }
        }
        if (!interfaces.isEmpty()) {
            boolean isInterface = !"CLASS".equals(ct);
            sb.append(isInterface ? " extends " : " implements ");
            String rendered = interfaces.stream()
                .map(i -> {
                    String simple = i.contains(".") ? i.substring(i.lastIndexOf('.') + 1) : i;
                    // For MyBatis-Plus mappers (XxxMapper extends BaseMapper<Entity>),
                    // derive the entity type from the mapper name by stripping the "Mapper" suffix.
                    // e.g. TutorialMapper → BaseMapper<Tutorial>
                    if ("BaseMapper".equals(simple) && cls.simpleName().endsWith("Mapper")) {
                        String entity = cls.simpleName().substring(0, cls.simpleName().length() - 6); // strip "Mapper"
                        if (!entity.isEmpty()) {
                            return "BaseMapper<" + entity + ">";
                        }
                    }
                    return simple;
                })
                .collect(Collectors.joining(", "));
            sb.append(rendered);
        }
        sb.append(" {\n\n");

        // Fields
        for (FieldEntry field : usedFields) {
            if (field.annotationParams() != null && !field.annotationParams().isBlank()) {
                sb.append("    ").append(formatFieldAnnotation(field)).append("\n");
            }
            sb.append("    ");
            if (field.modifiers() != null && !field.modifiers().isBlank()) {
                sb.append(normalizeModifiers(field.modifiers())).append(" ");
            }
            sb.append(field.fieldType()).append(" ").append(field.fieldName()).append(";\n\n");
        }
        if (!usedFields.isEmpty()) sb.append("\n");

        // Methods
        for (MethodRow method : involvedMethods) {
            String source = method.sourceCode();
            if (source != null) {
                // Source code already includes annotations, signature, and body.
                // Indent each line.
                String[] lines = source.split("\n");
                for (String line : lines) {
                    sb.append("    ").append(line).append("\n");
                }
            } else {
                // No source code — render annotations from DB as fallback
                List<AnnotationRow> methodAnnotations = loadMethodAnnotationsSync(cls.id(), method.signature());
                for (AnnotationRow ann : methodAnnotations) {
                    sb.append("    ").append(formatAnnotation(ann)).append("\n");
                }
                sb.append("    // source not available\n");
            }
            sb.append("\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String normalizeModifiers(String modifiers) {
        if (modifiers == null) return "";
        return modifiers.replace(", ", " ").replace(",", " ").replaceAll("\\s+", " ").trim();
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

    private ClassInfoRow loadClassInfo(Long projectId, String qualifiedName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id, qualified_name, simple_name, class_type, modifiers, source_file_path " +
                "FROM class_info WHERE project_id = ? AND qualified_name = ? LIMIT 1")) {
            ps.setLong(1, projectId);
            ps.setString(2, qualifiedName);
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
        // Query annotation_info for FIELD-level annotations on this field.
        // Need to join through field_info to get field_info.id for the target_id.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT ai.annotation_name, ai.annotation_params " +
                "FROM annotation_info ai " +
                "JOIN field_info fi ON ai.target_id = fi.id AND ai.target_type = 'FIELD' " +
                "WHERE fi.class_id = ? AND fi.field_name = ?")) {
            ps.setLong(1, classId);
            ps.setString(2, fieldName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString("annotation_name");
                    String params = rs.getString("annotation_params");
                    if (params != null && !params.isBlank() && !"{}".equals(params)) {
                        return name + ":" + params;
                    }
                    return name;
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to load field annotation for {}.{}: {}", classId, fieldName, e.getMessage());
        }
        return null;
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

    private Set<String> collectImportDependencies(Long classId, List<MethodRow> methods,
                                                    List<FieldEntry> fields, Long projectId,
                                                    String preferredPackage) {
        Set<String> imports = new LinkedHashSet<>();

        // 1. Collect from class_dependency table (resolved by JavaParser)
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

        // 2. Resolve field types to FQN via class_info
        //    JavaParser may not have stored field-type dependencies, so we
        //    search class_info across ALL projects for matching simple names.
        imports.addAll(resolveSimpleNamesToFqn(fields.stream().map(FieldEntry::fieldType).toList(), "field", preferredPackage));

        // 3. Resolve method return types and parameter types
        //    Types like "Result<TutorialResp>" are used in method signatures
        //    but may not be in class_dependency. Extract simple class names
        //    from return_type and parameter_types columns.
        List<String> methodTypes = methods.stream()
            .flatMap(m -> {
                List<String> types = new ArrayList<>();
                if (m.returnType() != null) types.add(m.returnType());
                if (m.parameterTypes() != null) {
                    // parameterTypes is JSON-like or comma-separated: "String,String,Result<TutorialResp>"
                    for (String pt : m.parameterTypes().split("[,;]")) {
                        pt = pt.trim().replaceAll("[\\[\\]\"{}]", "");
                        if (!pt.isEmpty()) types.add(pt);
                    }
                }
                return types.stream();
            })
            .toList();
        imports.addAll(resolveSimpleNamesToFqn(methodTypes, "method", preferredPackage));

        return imports;
    }

    /** Extract simple names and FQN from type strings, resolve to class_info FQN. */
    private Set<String> resolveSimpleNamesToFqn(List<String> typeStrings, String context,
                                                  String preferredPackage) {
        Set<String> lookups = new LinkedHashSet<>();
        for (String raw : typeStrings) {
            if (raw == null || raw.isBlank()) continue;
            // Handle generic: "Result<TutorialResp>" or "com.result.Result<TutorialResp>"
            String base = raw.contains("<") ? raw.substring(0, raw.indexOf('<')) : raw;
            if (!base.contains(".") && Character.isUpperCase(base.charAt(0)) && !isJavaLangClass(base)) {
                lookups.add(base); // simple name → search via LIKE
            } else if (base.contains(".") && !base.startsWith("java.")
                    && !base.startsWith("jakarta.") && !base.startsWith("org.springframework")) {
                lookups.add(base); // already FQN → direct match
            }
            // Also extract generic parameters
            if (raw.contains("<") && raw.contains(">")) {
                String inner = raw.substring(raw.indexOf('<') + 1, raw.lastIndexOf('>'));
                for (String part : inner.split(",")) {
                    part = part.trim();
                    if (part.contains(".")) {
                        lookups.add(part);
                    } else if (!part.isEmpty() && Character.isUpperCase(part.charAt(0)) && !isJavaLangClass(part)) {
                        lookups.add(part);
                    }
                }
            }
        }
        if (lookups.isEmpty()) return Set.of();

        // Build WHERE clause: FQN uses =, simple names use LIKE
        List<String> fqns = lookups.stream().filter(n -> n.contains(".")).toList();
        List<String> simples = lookups.stream().filter(n -> !n.contains(".")).toList();
        List<String> clauses = new ArrayList<>();
        List<String> params = new ArrayList<>();
        for (String f : fqns) { clauses.add("qualified_name = ?"); params.add(f); }
        for (String s : simples) { clauses.add("qualified_name LIKE ?"); params.add("%." + s); }
        if (clauses.isEmpty()) return Set.of();

        Set<String> result = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT qualified_name FROM class_info WHERE "
                + String.join(" OR ", clauses))) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            // For simple-name lookups (e.g. "TutorialService"), multiple projects may match.
            // Disambiguate by preferring the one with the longest common package prefix
            // to the anchor class (e.g. prefer device-server over management-server
            // when anchor is in com.nothing.watch.device.server).
            Map<String, List<String>> simpleToFqns = new LinkedHashMap<>();
            for (String s : simples) {
                simpleToFqns.put(s, new ArrayList<>());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String qn = rs.getString("qualified_name");
                    result.add(qn);
                    // Track per-simple-name matches for disambiguation
                    for (String s : simples) {
                        if (qn.endsWith("." + s) || qn.equals(s)) {
                            simpleToFqns.computeIfAbsent(s, k -> new ArrayList<>()).add(qn);
                            break;
                        }
                    }
                }
            }

            // Disambiguate: if multiple FQN match the same simple name, keep only the
            // one that shares the longest common package prefix with preferredPackage.
            if (preferredPackage != null && !preferredPackage.isEmpty()) {
                List<String> toRemove = new ArrayList<>();
                for (String s : simples) {
                    List<String> candidates = simpleToFqns.get(s);
                    if (candidates != null && candidates.size() > 1) {
                        // Find best match by shared prefix length
                        String best = candidates.stream()
                            .max(java.util.Comparator.comparingInt(
                                c -> commonPrefixLength(c, preferredPackage)))
                            .orElse(candidates.get(0));
                        for (String c : candidates) {
                            if (!c.equals(best)) toRemove.add(c);
                        }
                    }
                }
                result.removeAll(toRemove);
            }
        } catch (SQLException e) {
            log.debug("Failed to resolve {} types: {}", context, e.getMessage());
        }
        return result;
    }

    // ── Formatting Helpers ─────────────────────────────────────────

    private String formatAnnotation(AnnotationRow ann) {
        if (ann.annotationParams() == null || ann.annotationParams().isBlank()
            || ann.annotationParams().equals("{}")) {
            return "@" + ann.annotationName();
        }
        // annotationParams is stored as JSON-like string, e.g., {"value":"\"/api\""}
        // Convert to Java annotation syntax like @RequestMapping("/api") or @RequestMapping(value = "/api")
        String raw = ann.annotationParams()
            .replaceAll("\\\\\"", "\"")   // unescape inner quotes
            .trim();

        String javaParams;
        if (raw.startsWith("{") && raw.endsWith("}")) {
            // Parse JSON-like object: {"name":"value","flag":true}
            String inner = raw.substring(1, raw.length() - 1).trim();
            if (inner.isEmpty() || inner.equals("{}")) {
                return "@" + ann.annotationName();
            }
            // Split by comma at top level (not inside strings)
            List<String> pairs = splitAnnotationPairs(inner);
            if (pairs.size() == 1 && pairs.get(0).startsWith("\"value\":")) {
                // Single value: strip the "value": wrapper
                String val = pairs.get(0).substring(8).trim(); // after "value":
                javaParams = unquoteJsonValue(val);
            } else {
                // Multiple or non-value keys: convert each to key = value
                javaParams = pairs.stream()
                    .map(p -> {
                        int colon = p.indexOf(':');
                        if (colon < 0) return p.trim();
                        String key = unquoteJsonValue(p.substring(0, colon));
                        String val = unquoteJsonValue(p.substring(colon + 1));
                        return key + " = " + val;
                    })
                    .collect(Collectors.joining(", "));
            }
        } else if (raw.startsWith("\"") && raw.endsWith("\"")) {
            // Just a quoted string value
            javaParams = raw.substring(1, raw.length() - 1);
        } else {
            javaParams = raw;
        }

        return "@" + ann.annotationName() + "(" + javaParams + ")";
    }

    /** Split JSON key:value pairs at the top level (not inside nested strings/objects). */
    private List<String> splitAnnotationPairs(String inner) {
        List<String> pairs = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inString = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"' && (i == 0 || inner.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == '{' || c == '[') depth++;
            if (c == '}' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                pairs.add(inner.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < inner.length()) {
            pairs.add(inner.substring(start).trim());
        }
        return pairs;
    }

    /** Remove surrounding quotes from a JSON value. */
    private String unquoteJsonValue(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String formatFieldAnnotation(FieldEntry field) {
        String raw = field.annotationParams();
        if (raw == null || raw.isBlank()) return null;
        // Format: "Resource" or "Autowired:{\"name\":\"myBean\"}"
        if (raw.contains(":")) {
            int colon = raw.indexOf(':');
            String name = raw.substring(0, colon);
            String params = raw.substring(colon + 1);
            if (params != null && !params.isBlank() && !"{}".equals(params)) {
                String formatted = formatAnnotation(
                    new AnnotationRow(name, params, null, null));
                // formatAnnotation already returns "@Name(...)" — remove leading @
                return formatted.startsWith("@") ? formatted : "@" + formatted;
            }
            return "@" + name;
        }
        return "@" + raw;
    }

    /**
     * Match a MySQL short-name signature against a set of chain (FQN) signatures.
     * Normalizes both sides by stripping package prefixes from parameter types
     * before comparison, so that {@code getTutorial(String,String):Result}
     * matches {@code getTutorial(java.lang.String,java.lang.String):com...Result}.
     */
    private boolean signatureMatch(String mysqlSig, Set<String> chainSigs) {
        // Exact match
        if (chainSigs.contains(mysqlSig)) return true;
        // Normalized match: strip spaces so "String,String" == "String, String"
        String normalized = mysqlSig.replace(" ", "");
        return chainSigs.stream().anyMatch(s -> s.replace(" ", "").equals(normalized));
    }

    /** Count chars in common prefix between two dotted package/class names. */
    private int commonPrefixLength(String a, String b) {
        int min = Math.min(a.length(), b.length());
        int i = 0;
        while (i < min && a.charAt(i) == b.charAt(i)) i++;
        return i;
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
