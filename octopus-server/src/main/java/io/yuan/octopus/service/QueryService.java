package io.yuan.octopus.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.dto.response.*;
import io.yuan.octopus.dto.response.AnalysisReportDTO.*;
import io.yuan.octopus.dto.response.CallChainDTO.CallChainNodeDTO;
import io.yuan.octopus.entity.*;
import io.yuan.octopus.exception.ResourceNotFoundException;
import io.yuan.octopus.repository.*;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private final Driver neo4jDriver;
    private final ClassInfoRepository classInfoRepository;
    private final MethodInfoRepository methodInfoRepository;
    private final FieldInfoRepository fieldInfoRepository;
    private final AnnotationInfoRepository annotationInfoRepository;
    private final InheritanceRelationRepository inheritanceRelationRepository;
    private final ClassDependencyRepository classDependencyRepository;
    private final ClassInterfaceRelationRepository classInterfaceRelationRepository;
    private final JavaProjectRepository javaProjectRepository;
    private final Gson gson = new Gson();

    public QueryService(Driver neo4jDriver,
                        ClassInfoRepository classInfoRepository,
                        MethodInfoRepository methodInfoRepository,
                        FieldInfoRepository fieldInfoRepository,
                        AnnotationInfoRepository annotationInfoRepository,
                        InheritanceRelationRepository inheritanceRelationRepository,
                        ClassDependencyRepository classDependencyRepository,
                        ClassInterfaceRelationRepository classInterfaceRelationRepository,
                        JavaProjectRepository javaProjectRepository) {
        this.neo4jDriver = neo4jDriver;
        this.classInfoRepository = classInfoRepository;
        this.methodInfoRepository = methodInfoRepository;
        this.fieldInfoRepository = fieldInfoRepository;
        this.annotationInfoRepository = annotationInfoRepository;
        this.inheritanceRelationRepository = inheritanceRelationRepository;
        this.classDependencyRepository = classDependencyRepository;
        this.classInterfaceRelationRepository = classInterfaceRelationRepository;
        this.javaProjectRepository = javaProjectRepository;
    }

    // ==================== Neo4j Query Methods ====================

    public List<MethodEdgeDTO> getCallers(String qualifiedClassName, String methodName, List<String> paramTypes) {
        String cypher = """
            MATCH (caller:MethodNode)-[c:CALLS]->(callee:MethodNode)
            WHERE callee.qualifiedClassName = $className AND callee.methodName = $methodName
              AND coalesce(caller.current, true) = true AND coalesce(callee.current, true) = true
            RETURN caller, c.callType AS callType, c.lineNumber AS lineNumber
            """;
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, Map.of("className", qualifiedClassName, "methodName", methodName));
            List<MethodEdgeDTO> callers = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                Node callerNode = record.get("caller").asNode();
                String callType = record.get("callType").isNull() ? "DIRECT" : record.get("callType").asString();
                int lineNumber = record.get("lineNumber").isNull() ? 0 : record.get("lineNumber").asInt();
                callers.add(mapNodeToMethodEdgeDTO(callerNode, callType, lineNumber));
            }
            return callers;
        }
    }

    public List<MethodEdgeDTO> getCallees(String qualifiedClassName, String methodName, List<String> paramTypes) {
        String cypher = """
            MATCH (caller:MethodNode)-[c:CALLS]->(callee:MethodNode)
            WHERE caller.qualifiedClassName = $className AND caller.methodName = $methodName
              AND coalesce(caller.current, true) = true AND coalesce(callee.current, true) = true
            RETURN callee, c.callType AS callType, c.lineNumber AS lineNumber
            """;
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, Map.of("className", qualifiedClassName, "methodName", methodName));
            List<MethodEdgeDTO> callees = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                Node calleeNode = record.get("callee").asNode();
                String callType = record.get("callType").isNull() ? "DIRECT" : record.get("callType").asString();
                int lineNumber = record.get("lineNumber").isNull() ? 0 : record.get("lineNumber").asInt();
                callees.add(mapNodeToMethodEdgeDTO(calleeNode, callType, lineNumber));
            }
            return callees;
        }
    }

    public CallChainDTO getCallChain(String qualifiedClassName, String methodName, List<String> paramTypes, int maxDepth) {
        // maxDepth <= 0 means unlimited depth
        String depthSpec = maxDepth > 0 ? "1.." + maxDepth : "1..";
        // Exclude SPRING_DI edges: they represent class-level DI dependencies, not method invocations.
        // Including them would produce paths that terminate at virtual class nodes (not real methods),
        // making the result a mixed dependency graph rather than a pure directed method-call graph.
        String cypher = String.format("""
            MATCH path = (start:MethodNode)-[:CALLS*%s]->(end:MethodNode)
            WHERE start.qualifiedClassName = $className AND start.methodName = $methodName
              AND ALL(r IN relationships(path) WHERE r.callType <> 'SPRING_DI')
              AND ALL(n IN nodes(path) WHERE coalesce(n.current, true) = true)
            RETURN path
            """, depthSpec);
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, Map.of("className", qualifiedClassName, "methodName", methodName));

            MethodNodeDTO root = null;
            Map<String, List<CallChainNodeDTO>> childrenMap = new LinkedHashMap<>();

            while (result.hasNext()) {
                Record record = result.next();
                Path path = record.get("path").asPath();

                Node startNode = path.start();
                if (root == null) {
                    root = mapNodeToMethodNodeDTO(startNode);
                }

                // Build chain from path segments
                for (Path.Segment segment : path) {
                    Node startSeg = segment.start();
                    Node endSeg = segment.end();
                    String parentSig = startSeg.get("signature").asString();

                    MethodNodeDTO childMethod = mapNodeToMethodNodeDTO(endSeg);
                    String callType = segment.relationship().get("callType").isNull()
                        ? "DIRECT" : segment.relationship().get("callType").asString();
                    int lineNumber = segment.relationship().get("lineNumber").isNull()
                        ? 0 : segment.relationship().get("lineNumber").asInt();

                    // Deduplicate: use a Map keyed by (childSig + "|" + callType) per parent
                    String childSig = childMethod.signature();
                    String dedupKey = (childSig != null ? childSig : childMethod.methodName()) + "|" + callType;
                    childrenMap.computeIfAbsent(parentSig, k -> new ArrayList<>());
                    boolean alreadyPresent = childrenMap.get(parentSig).stream()
                        .anyMatch(existing -> {
                            String existSig = existing.method().signature();
                            String existKey = (existSig != null ? existSig : existing.method().methodName()) + "|" + existing.callType();
                            return existKey.equals(dedupKey);
                        });
                    if (!alreadyPresent) {
                        CallChainNodeDTO childNode = new CallChainNodeDTO(childMethod, callType, lineNumber, new ArrayList<>());
                        childrenMap.get(parentSig).add(childNode);
                    }
                }
            }

            if (root == null) {
                // Return empty chain if no paths found
                return new CallChainDTO(
                    new MethodNodeDTO(qualifiedClassName, methodName, paramTypes, null, null, null),
                    List.of()
                );
            }

            // Build tree from root signature
            List<CallChainNodeDTO> chain = buildChainTree(root.signature(), childrenMap, new HashSet<>());
            return new CallChainDTO(root, chain);
        }
    }

    /**
     * Returns both upstream callers and downstream callees for a method, up to the given depth.
     * Each level of depth traverses one CALLS relationship.
     */
    public CallTraceDTO traceMethod(String qualifiedClassName, String methodName, List<String> paramTypes, int depth) {
        int effectiveDepth = depth <= 0 ? 5 : depth;

        // Upstream: callers that call this method (reverse direction)
        List<MethodEdgeDTO> upstream = new ArrayList<>();
        String upstreamCypher = String.format("""
            MATCH path = (caller:MethodNode)-[c:CALLS*1..%d]->(callee:MethodNode)
            WHERE callee.qualifiedClassName = $className AND callee.methodName = $methodName
              AND ALL(n IN nodes(path) WHERE coalesce(n.current, true) = true)
              AND ALL(r IN relationships(path) WHERE r.callType <> 'SPRING_DI')
            RETURN caller, last(c).callType AS callType, last(c).lineNumber AS lineNumber
            """, effectiveDepth);
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(upstreamCypher, Map.of("className", qualifiedClassName, "methodName", methodName));
            while (result.hasNext()) {
                Record record = result.next();
                Node callerNode = record.get("caller").asNode();
                String callType = record.get("callType").isNull() ? "DIRECT" : record.get("callType").asString();
                int lineNumber = record.get("lineNumber").isNull() ? 0 : record.get("lineNumber").asInt();
                MethodEdgeDTO dto = mapNodeToMethodEdgeDTO(callerNode, callType, lineNumber);
                if (upstream.stream().noneMatch(e -> e.signature().equals(dto.signature()))) {
                    upstream.add(dto);
                }
            }
        }

        // Downstream: methods called by this method (forward direction)
        List<MethodEdgeDTO> downstream = new ArrayList<>();
        String downstreamCypher = String.format("""
            MATCH path = (caller:MethodNode)-[c:CALLS*1..%d]->(callee:MethodNode)
            WHERE caller.qualifiedClassName = $className AND caller.methodName = $methodName
              AND ALL(n IN nodes(path) WHERE coalesce(n.current, true) = true)
              AND ALL(r IN relationships(path) WHERE r.callType <> 'SPRING_DI')
            RETURN callee, last(c).callType AS callType, last(c).lineNumber AS lineNumber
            """, effectiveDepth);
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(downstreamCypher, Map.of("className", qualifiedClassName, "methodName", methodName));
            while (result.hasNext()) {
                Record record = result.next();
                Node calleeNode = record.get("callee").asNode();
                String callType = record.get("callType").isNull() ? "DIRECT" : record.get("callType").asString();
                int lineNumber = record.get("lineNumber").isNull() ? 0 : record.get("lineNumber").asInt();
                MethodEdgeDTO dto = mapNodeToMethodEdgeDTO(calleeNode, callType, lineNumber);
                if (downstream.stream().noneMatch(e -> e.signature().equals(dto.signature()))) {
                    downstream.add(dto);
                }
            }
        }

        MethodNodeDTO anchor = new MethodNodeDTO(qualifiedClassName, methodName, paramTypes, null, null, null);
        return new CallTraceDTO(anchor, upstream, downstream);
    }

    private List<CallChainNodeDTO> buildChainTree(String parentSignature,
                                                   Map<String, List<CallChainNodeDTO>> childrenMap,
                                                   Set<String> visited) {
        if (visited.contains(parentSignature)) {
            return List.of();
        }
        visited.add(parentSignature);

        List<CallChainNodeDTO> children = childrenMap.getOrDefault(parentSignature, List.of());
        List<CallChainNodeDTO> result = new ArrayList<>();
        for (CallChainNodeDTO child : children) {
            List<CallChainNodeDTO> grandChildren = buildChainTree(
                child.method().signature(), childrenMap, new HashSet<>(visited));
            result.add(new CallChainNodeDTO(child.method(), child.callType(), child.lineNumber(), grandChildren));
        }
        return result;
    }

    private MethodNodeDTO mapNodeToMethodNodeDTO(Node node) {
        List<String> parameterTypes = new ArrayList<>();
        if (!node.get("parameterTypes").isNull()) {
            node.get("parameterTypes").asList(Value::asString).forEach(parameterTypes::add);
        }
        return new MethodNodeDTO(
            node.get("qualifiedClassName").isNull() ? null : node.get("qualifiedClassName").asString(),
            node.get("methodName").isNull() ? null : node.get("methodName").asString(),
            parameterTypes,
            node.get("returnType").isNull() ? null : node.get("returnType").asString(),
            node.get("modifiers").isNull() ? null : node.get("modifiers").asString(),
            node.get("signature").isNull() ? null : node.get("signature").asString()
        );
    }

    private MethodEdgeDTO mapNodeToMethodEdgeDTO(Node node, String callType, int lineNumber) {
        List<String> parameterTypes = new ArrayList<>();
        if (!node.get("parameterTypes").isNull()) {
            node.get("parameterTypes").asList(Value::asString).forEach(parameterTypes::add);
        }
        return new MethodEdgeDTO(
            node.get("qualifiedClassName").isNull() ? null : node.get("qualifiedClassName").asString(),
            node.get("methodName").isNull() ? null : node.get("methodName").asString(),
            parameterTypes,
            node.get("returnType").isNull() ? null : node.get("returnType").asString(),
            node.get("modifiers").isNull() ? null : node.get("modifiers").asString(),
            node.get("signature").isNull() ? null : node.get("signature").asString(),
            callType,
            lineNumber
        );
    }

    // ==================== MySQL Metadata Query Methods ====================

    public ClassDetailDTO getClassDetail(String qualifiedClassName) {
        ClassInfo classInfo = classInfoRepository.findByQualifiedName(qualifiedClassName)
            .orElseThrow(() -> new ResourceNotFoundException("Class not found: " + qualifiedClassName));

        List<MethodInfo> methods = methodInfoRepository.findByClassInfoId(classInfo.getId());
        List<FieldInfo> fields = fieldInfoRepository.findByClassId(classInfo.getId());
        List<AnnotationInfo> annotations = annotationInfoRepository.findByTargetTypeAndTargetId("CLASS", classInfo.getId());
        List<InheritanceRelation> inheritanceRelations = inheritanceRelationRepository.findByChildQualifiedName(qualifiedClassName);
        List<ClassDependency> dependencies = classDependencyRepository.findByClassId(classInfo.getId());
        List<ClassInterfaceRelation> interfaceRelations = classInterfaceRelationRepository.findByClassId(classInfo.getId());

        List<MethodDetailDTO> methodDTOs = methods.stream()
            .map(m -> new MethodDetailDTO(m.getId(), m.getMethodSignature(), m.getMethodName(),
                parseParamTypes(m.getParameterTypes()), m.getReturnType(), m.getModifiers(), m.getSourceCode()))
            .toList();

        List<FieldDetailDTO> fieldDTOs = fields.stream()
            .map(f -> new FieldDetailDTO(f.getId(), f.getFieldName(), f.getFieldType(), f.getModifiers()))
            .toList();

        List<AnnotationDetailDTO> annotationDTOs = annotations.stream()
            .map(a -> new AnnotationDetailDTO(a.getAnnotationName(), a.getAnnotationParams()))
            .toList();

        List<InheritanceDetailDTO> inheritanceDTOs = inheritanceRelations.stream()
            .map(i -> new InheritanceDetailDTO(i.getParentQualifiedName(), i.getRelationType()))
            .toList();

        List<ExternalDependencyDTO> dependencyDTOs = dependencies.stream()
            .map(d -> new ExternalDependencyDTO(d.getDependencyQualifiedName(), d.getDependencyType().name()))
            .toList();

        List<String> interfaces = interfaceRelations.stream()
            .map(ClassInterfaceRelation::getInterfaceQualifiedName)
            .toList();

        return new ClassDetailDTO(
            classInfo.getId(),
            classInfo.getQualifiedName(),
            classInfo.getSimpleName(),
            classInfo.getClassType() != null ? classInfo.getClassType().name() : null,
            classInfo.getModifiers(),
            classInfo.getSourceFilePath(),
            classInfo.getParentClass(),
            interfaces,
            methodDTOs,
            fieldDTOs,
            annotationDTOs,
            inheritanceDTOs,
            dependencyDTOs
        );
    }

    public List<ClassSummaryDTO> listClassesByPackage(String packageName) {
        List<ClassInfo> classes = classInfoRepository.findByQualifiedNameStartingWith(packageName);
        return classes.stream()
            .map(c -> new ClassSummaryDTO(
                c.getId(),
                c.getQualifiedName(),
                c.getSimpleName(),
                c.getClassType() != null ? c.getClassType().name() : null,
                c.getModifiers()))
            .toList();
    }

    public List<ClassSummaryDTO> getImplementors(String interfaceName) {
        List<InheritanceRelation> relations =
            inheritanceRelationRepository.findByParentQualifiedNameAndRelationType(interfaceName, "IMPLEMENTS");
        return relations.stream()
            .map(r -> classInfoRepository.findByQualifiedName(r.getChildQualifiedName()).orElse(null))
            .filter(java.util.Objects::nonNull)
            .map(c -> new ClassSummaryDTO(
                c.getId(),
                c.getQualifiedName(),
                c.getSimpleName(),
                c.getClassType() != null ? c.getClassType().name() : null,
                c.getModifiers()))
            .toList();
    }

    public List<AnnotatedElementDTO> findByAnnotation(String annotationName) {
        List<AnnotationInfo> annotations = annotationInfoRepository.findByAnnotationName(annotationName);
        List<AnnotatedElementDTO> result = new ArrayList<>();

        for (AnnotationInfo annotation : annotations) {
            String elementType = annotation.getTargetType();
            String elementName = null;
            String qualifiedClassName = null;

            if ("CLASS".equals(elementType)) {
                var classOpt = classInfoRepository.findById(annotation.getTargetId());
                if (classOpt.isPresent()) {
                    ClassInfo classInfo = classOpt.get();
                    elementName = classInfo.getSimpleName();
                    qualifiedClassName = classInfo.getQualifiedName();
                }
            } else if ("METHOD".equals(elementType)) {
                var methodOpt = methodInfoRepository.findById(annotation.getTargetId());
                if (methodOpt.isPresent()) {
                    MethodInfo methodInfo = methodOpt.get();
                    elementName = methodInfo.getMethodName();
                    qualifiedClassName = methodInfo.getClassInfo() != null
                        ? methodInfo.getClassInfo().getQualifiedName() : null;
                }
            }

            if (elementName != null) {
                result.add(new AnnotatedElementDTO(annotation.getAnnotationName(), elementType, elementName, qualifiedClassName));
            }
        }

        return result;
    }

    private List<String> parseParamTypes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return gson.fromJson(json, new TypeToken<List<String>>() {}.getType());
        } catch (Exception e) {
            return List.of(json);
        }
    }

    public List<EndpointDTO> getEndpoints(Long projectId) {
        List<String> httpAnnotations = List.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping"
        );

        // Resolve which method IDs to search, scoped to the project if requested
        List<AnnotationInfo> methodAnnotations;
        if (projectId != null) {
            List<Long> classIds = classInfoRepository.findByProjectId(projectId)
                    .stream().map(ClassInfo::getId).toList();
            if (classIds.isEmpty()) return List.of();
            List<Long> methodIds = methodInfoRepository.findByClassInfoIdIn(classIds)
                    .stream().map(MethodInfo::getId).toList();
            if (methodIds.isEmpty()) return List.of();
            methodAnnotations = annotationInfoRepository
                    .findByAnnotationNameInAndTargetTypeAndTargetIdIn(httpAnnotations, "METHOD", methodIds);
        } else {
            methodAnnotations = annotationInfoRepository.findByAnnotationNameIn(httpAnnotations)
                    .stream().filter(a -> "METHOD".equals(a.getTargetType())).toList();
        }

        if (methodAnnotations.isEmpty()) return List.of();

        // Batch-load MethodInfo (avoids N+1)
        Set<Long> methodIdSet = methodAnnotations.stream().map(AnnotationInfo::getTargetId).collect(Collectors.toSet());
        Map<Long, MethodInfo> methodById = new HashMap<>();
        methodInfoRepository.findAllById(methodIdSet).forEach(m -> methodById.put(m.getId(), m));

        // Collect unique ClassInfo IDs and batch-load project entities + class-level RequestMapping annotations
        Set<Long> classIdSet = methodById.values().stream()
                .filter(m -> m.getClassInfo() != null)
                .map(m -> m.getClassInfo().getId())
                .collect(Collectors.toSet());

        Map<Long, JavaProjectEntity> projectByClassId = new HashMap<>();
        Map<Long, String> classPrefixById = new HashMap<>();
        if (!classIdSet.isEmpty()) {
            // Batch-load class-level RequestMapping annotations for prefix combination
            List<AnnotationInfo> classAnnotations = annotationInfoRepository
                    .findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                            List.of("RequestMapping"), "CLASS", new ArrayList<>(classIdSet));
            classAnnotations.forEach(ca ->
                    classPrefixById.put(ca.getTargetId(), parseAnnotationPath(ca.getAnnotationParams())));

            // Batch-load JavaProjectEntity via ClassInfo.projectId
            Set<Long> projectIds = new HashSet<>();
            classInfoRepository.findAllById(classIdSet).forEach(ci -> {
                if (ci.getProjectId() != null) projectIds.add(ci.getProjectId());
            });
            Map<Long, JavaProjectEntity> projectById = new HashMap<>();
            javaProjectRepository.findAllById(projectIds).forEach(p -> projectById.put(p.getId(), p));

            // Map classId → project entity via ClassInfo
            classInfoRepository.findAllById(classIdSet).forEach(ci -> {
                if (ci.getProjectId() != null) {
                    JavaProjectEntity proj = projectById.get(ci.getProjectId());
                    if (proj != null) projectByClassId.put(ci.getId(), proj);
                }
            });
        }

        List<EndpointDTO> result = new ArrayList<>();
        for (AnnotationInfo ann : methodAnnotations) {
            MethodInfo m = methodById.get(ann.getTargetId());
            if (m == null) continue;
            ClassInfo classInfo = m.getClassInfo();
            if (classInfo == null) continue;

            String qualifiedClassName = classInfo.getQualifiedName();
            String simpleName = qualifiedClassName != null
                    ? qualifiedClassName.substring(qualifiedClassName.lastIndexOf('.') + 1) : null;

            String httpMethod = parseRequestMappingMethod(ann);
            if (httpMethod == null) {
                httpMethod = ann.getAnnotationName().replace("Mapping", "").toUpperCase();
                if ("REQUEST".equals(httpMethod)) httpMethod = "*";
            }

            String methodPath = parseAnnotationPath(ann.getAnnotationParams());
            String classPrefix = classPrefixById.getOrDefault(classInfo.getId(), "");
            String fullPath = combinePaths(classPrefix, methodPath);

            JavaProjectEntity project = projectByClassId.get(classInfo.getId());
            result.add(new EndpointDTO(
                    httpMethod, fullPath,
                    m.getMethodName(), qualifiedClassName, simpleName, m.getMethodSignature(),
                    project != null ? project.getId() : null,
                    project != null ? project.getProjectName() : null,
                    project != null ? project.getBranch() : null,
                    project != null ? project.getCommitId() : null));
        }

        result.sort(Comparator
                .comparing((EndpointDTO e) -> e.qualifiedClassName() != null ? e.qualifiedClassName() : "")
                .thenComparing(e -> e.methodName() != null ? e.methodName() : ""));
        return result;
    }

    public List<ProjectSummaryDTO> getProjects() {
        return javaProjectRepository.findAll().stream()
                .map(p -> new ProjectSummaryDTO(
                        p.getId(),
                        p.getProjectName(),
                        p.getProjectPath(),
                        p.getBranch(),
                        p.getCommitId(),
                        p.getIsBackendProject(),
                        p.getIsApiService(),
                        p.getBuildTool() != null ? p.getBuildTool().name() : null,
                        p.getJavaVersion() != null ? p.getJavaVersion().name() : null))
                .sorted(Comparator.comparing(ProjectSummaryDTO::projectName)
                        .thenComparing(p -> p.branch() != null ? p.branch() : ""))
                .toList();
    }

    // ==================== Analysis Report / Verification ====================

    /**
     * Generates a comprehensive correctness report for the analysis results stored in Neo4j and MySQL.
     *
     * <p>The report covers:
     * <ol>
     *   <li><b>Graph statistics</b> – node/edge counts queried from Neo4j, CallType breakdown,
     *       isolated / root / leaf node counts, virtual placeholder node count.</li>
     *   <li><b>Metadata statistics</b> – class / method / field counts per project from MySQL.</li>
     *   <li><b>Cross-validation</b> – compares Neo4j and MySQL counts and samples mismatches.</li>
     *   <li><b>Detected issues</b> – automatically flags potential problems.</li>
     * </ol>
     *
     * @param projectNameFilter optional project name substring filter; pass {@code null} or empty to report all
     */
    public AnalysisReportDTO getAnalysisReport(String projectNameFilter) {
        // ── 1. Graph statistics from Neo4j ──────────────────────────────────
        GraphStats graphStats = buildGraphStats();

        // ── 2. Metadata statistics from MySQL ───────────────────────────────
        List<JavaProjectEntity> projects = projectNameFilter != null && !projectNameFilter.isBlank()
                ? javaProjectRepository.findAll().stream()
                    .filter(p -> p.getProjectName() != null
                            && p.getProjectName().toLowerCase().contains(projectNameFilter.toLowerCase()))
                    .toList()
                : javaProjectRepository.findAll();

        List<MetadataStats> metadataStatsList = buildMetadataStats(projects);

        // ── 3. Cross-validation ──────────────────────────────────────────────
        CrossValidation crossValidation = buildCrossValidation(graphStats, metadataStatsList);

        // ── 4. Issue detection ───────────────────────────────────────────────
        IssueList issues = detectIssues(graphStats, metadataStatsList, crossValidation, projects);

        // ── 5. Overall verdict ───────────────────────────────────────────────
        String verdict = deriveVerdict(issues, graphStats, metadataStatsList);

        return new AnalysisReportDTO(graphStats, metadataStatsList, crossValidation, issues, verdict);
    }

    private GraphStats buildGraphStats() {
        try (Session session = neo4jDriver.session()) {
            // Total node count
            long totalNodes = session.run("MATCH (n:MethodNode) WHERE coalesce(n.current, true) = true RETURN count(n) AS c")
                    .single().get("c").asLong();

            // Total edge count
            long totalEdges = session.run("MATCH (a)-[r:CALLS]->(b) WHERE coalesce(a.current, true) = true AND coalesce(b.current, true) = true RETURN count(r) AS c")
                    .single().get("c").asLong();

            // Edge breakdown by CallType
            Map<String, Long> edgesByCallType = new LinkedHashMap<>();
            session.run("MATCH (a)-[r:CALLS]->(b) WHERE coalesce(a.current, true) = true AND coalesce(b.current, true) = true RETURN r.callType AS t, count(r) AS c ORDER BY c DESC")
                    .list().forEach(rec -> edgesByCallType.put(
                            rec.get("t").isNull() ? "UNKNOWN" : rec.get("t").asString(),
                            rec.get("c").asLong()));

            // Isolated nodes (no in, no out)
            long isolatedCount = session.run(
                    "MATCH (n:MethodNode) WHERE coalesce(n.current, true) = true AND NOT (n)-[:CALLS]->() AND NOT ()-[:CALLS]->(n) RETURN count(n) AS c")
                    .single().get("c").asLong();

            // Nodes with no outgoing edge (leaf methods)
            long noOutgoing = session.run(
                    "MATCH (n:MethodNode) WHERE coalesce(n.current, true) = true AND NOT (n)-[:CALLS]->() RETURN count(n) AS c")
                    .single().get("c").asLong();

            // Nodes with no incoming edge (potential entry-points)
            long noIncoming = session.run(
                    "MATCH (n:MethodNode) WHERE coalesce(n.current, true) = true AND NOT ()-[:CALLS]->(n) RETURN count(n) AS c")
                    .single().get("c").asLong();

            // Virtual/placeholder nodes: methodName equals simpleName of qualifiedClassName
            // (created by findOrCreateClassNode for DI / Scheduler targets)
            long virtualNodes = session.run(
                    "MATCH (n:MethodNode) WHERE coalesce(n.current, true) = true AND n.returnType = 'void' AND n.modifiers = '' "
                    + "AND n.methodName = split(n.qualifiedClassName, '.')[-1] RETURN count(n) AS c")
                    .single().get("c").asLong();

            return new GraphStats(totalNodes, totalEdges, edgesByCallType,
                    isolatedCount, noOutgoing, noIncoming, virtualNodes);
        }
    }

    private List<MetadataStats> buildMetadataStats(List<JavaProjectEntity> projects) {
        return projects.stream().map(p -> {
            // Use filtered queries instead of findAll() + in-memory stream filter
            List<ClassInfo> projectClasses = p.getBranch() != null
                    ? classInfoRepository.findByProjectIdAndBranch(p.getId(), p.getBranch())
                    : classInfoRepository.findByProjectId(p.getId());
            long classCount = projectClasses.size();

            // Batch-load method and field counts via in-clause queries
            List<Long> classIds = projectClasses.stream()
                    .map(ClassInfo::getId)
                    .toList();
            long methodCount = 0;
            long fieldCount = 0;
            if (!classIds.isEmpty()) {
                for (Long cid : classIds) {
                    methodCount += methodInfoRepository.findByClassInfoId(cid).size();
                    fieldCount += fieldInfoRepository.findByClassId(cid).size();
                }
            }
            return new MetadataStats(p.getId(), p.getProjectName(), p.getBranch(),
                    p.getAnalyzedTag(), classCount, methodCount, fieldCount);
        }).toList();
    }

    private CrossValidation buildCrossValidation(GraphStats graphStats,
                                                  List<MetadataStats> metadataStatsList) {
        long mysqlClassCount = metadataStatsList.stream().mapToLong(MetadataStats::classCount).sum();
        long mysqlMethodCount = metadataStatsList.stream().mapToLong(MetadataStats::methodCount).sum();
        long neo4jNodeCount = graphStats.totalNodes();

        // Distinct class names from Neo4j
        Set<String> neo4jClasses;
        Set<String> mysqlClasses;
        try (Session session = neo4jDriver.session()) {
            neo4jClasses = new HashSet<>(session.run(
                    "MATCH (n:MethodNode) WHERE coalesce(n.current, true) = true RETURN DISTINCT n.qualifiedClassName AS c")
                    .list(r -> r.get("c").asString()));
        }
        mysqlClasses = classInfoRepository.findAll().stream()
                .map(ClassInfo::getQualifiedName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        long neo4jClassCount = neo4jClasses.size();

        // Classes only in MySQL (possibly missing from Neo4j due to filter)
        Set<String> onlyInMysql = new HashSet<>(mysqlClasses);
        onlyInMysql.removeAll(neo4jClasses);

        // Classes only in Neo4j (possibly third-party that slipped through)
        Set<String> onlyInNeo4j = new HashSet<>(neo4jClasses);
        onlyInNeo4j.removeAll(mysqlClasses);

        // Tolerance: within 20%
        boolean classMatch = mysqlClassCount == 0 || neo4jClassCount == 0 ||
                Math.abs(neo4jClassCount - mysqlClassCount) * 100.0 / mysqlClassCount <= 20;
        boolean methodMatch = mysqlMethodCount == 0 || neo4jNodeCount == 0 ||
                Math.abs(neo4jNodeCount - mysqlMethodCount) * 100.0 / mysqlMethodCount <= 20;

        List<String> sampleOnlyMysql = onlyInMysql.stream().sorted().limit(20).toList();
        List<String> sampleOnlyNeo4j = onlyInNeo4j.stream().sorted().limit(20).toList();

        return new CrossValidation(neo4jNodeCount, mysqlMethodCount, mysqlClassCount,
                neo4jClassCount, classMatch, methodMatch, sampleOnlyMysql, sampleOnlyNeo4j);
    }

    private IssueList detectIssues(GraphStats graphStats, List<MetadataStats> metadataStatsList,
                                   CrossValidation crossValidation, List<JavaProjectEntity> projects) {
        List<String> items = new ArrayList<>();

        // No projects analysed at all
        if (projects.isEmpty()) {
            items.add("[CRITICAL] No project records found — analysis may not have been run yet.");
        }

        // Projects that were never successfully tagged
        projects.stream()
                .filter(p -> p.getAnalyzedTag() == null || p.getAnalyzedAt() == null)
                .forEach(p -> items.add("[WARNING] Project '" + p.getProjectName()
                        + "' (branch=" + p.getBranch() + ") has no analysed tag — analysis may be incomplete."));

        // Graph is empty
        if (graphStats.totalNodes() == 0) {
            items.add("[CRITICAL] Neo4j graph is empty — no method nodes were stored.");
        }
        if (graphStats.totalEdges() == 0 && graphStats.totalNodes() > 0) {
            items.add("[WARNING] Neo4j has nodes but zero CALLS edges — call graph was not built.");
        }

        // >50% isolated nodes is suspicious
        if (graphStats.totalNodes() > 0) {
            double isolatedRatio = (double) graphStats.isolatedNodeCount() / graphStats.totalNodes();
            if (isolatedRatio > 0.5) {
                items.add(String.format("[WARNING] %.0f%% of nodes are isolated (no edges) — possible parsing failure "
                        + "or aggressive third-party filtering.", isolatedRatio * 100));
            }
        }

        // Large number of virtual nodes suggests DI resolution failed
        if (graphStats.virtualNodeCount() > 50) {
            items.add("[INFO] " + graphStats.virtualNodeCount()
                    + " virtual placeholder nodes detected — some DI or Scheduler targets could not be resolved to actual methods.");
        }

        // MySQL is empty while Neo4j has data (metadata not stored)
        long totalMysqlMethods = metadataStatsList.stream().mapToLong(MetadataStats::methodCount).sum();
        if (graphStats.totalNodes() > 0 && totalMysqlMethods == 0) {
            items.add("[CRITICAL] MySQL has no method metadata while Neo4j has nodes — STORE_METADATA step may have failed.");
        }

        // Cross-validation: class count mismatch
        if (!crossValidation.classCountMatch()) {
            items.add(String.format("[WARNING] Class count mismatch: Neo4j has %d distinct classes, MySQL has %d classes (>20%% difference). "
                    + "Some classes may have been filtered out or not parsed.",
                    crossValidation.neo4jClassNodeCount(), crossValidation.mysqlClassCount()));
        }

        // Cross-validation: method count mismatch
        if (!crossValidation.methodCountMatch()) {
            items.add(String.format("[WARNING] Method node count mismatch: Neo4j has %d nodes, MySQL has %d methods (>20%% difference). "
                    + "JavaParser and PSI results may diverge significantly.",
                    crossValidation.neo4jMethodNodeCount(), crossValidation.mysqlMethodCount()));
        }

        // Classes in MySQL with no Neo4j node
        if (!crossValidation.classesOnlyInMysql().isEmpty()) {
            items.add("[INFO] " + crossValidation.classesOnlyInMysql().size()
                    + " classes exist in MySQL but have no nodes in Neo4j (sample: "
                    + String.join(", ", crossValidation.classesOnlyInMysql().stream().limit(5).toList()) + ").");
        }

        // Classes in Neo4j not in MySQL (third-party leak)
        if (!crossValidation.classesOnlyInNeo4j().isEmpty()) {
            items.add("[INFO] " + crossValidation.classesOnlyInNeo4j().size()
                    + " class names in Neo4j are absent from MySQL metadata (sample: "
                    + String.join(", ", crossValidation.classesOnlyInNeo4j().stream().limit(5).toList()) + ").");
        }

        return new IssueList(items.size(), items);
    }

    private String deriveVerdict(IssueList issues, GraphStats graphStats,
                                  List<MetadataStats> metadataStatsList) {
        boolean hasCritical = issues.items().stream().anyMatch(i -> i.startsWith("[CRITICAL]"));
        boolean hasWarning  = issues.items().stream().anyMatch(i -> i.startsWith("[WARNING]"));
        if (hasCritical) {
            return "INCOMPLETE — critical issues detected, analysis results are likely unreliable.";
        }
        if (hasWarning) {
            return "PARTIAL — warnings detected, analysis is likely mostly correct but may have gaps.";
        }
        if (graphStats.totalNodes() > 0 && graphStats.totalEdges() > 0) {
            return "OK — no critical or warning issues detected, analysis results appear complete.";
        }
        return "UNKNOWN — insufficient data to determine correctness.";
    }

    /**
     * Parses the HTTP method from a {@code @RequestMapping} annotation's stored parameters.
     * <p>
     * Spring's {@code @RequestMapping(method = RequestMethod.POST)} stores the {@code method}
     * parameter in the annotation params as {@code "method":"RequestMethod.POST"} (or
     * {@code "POST"} when statically imported).  Array-valued methods like
     * {@code {RequestMethod.POST, RequestMethod.GET}} produce
     * {@code "method":"{RequestMethod.POST,RequestMethod.GET}"}.
     * <p>
     * For non-{@code @RequestMapping} annotations — or when no {@code method} attribute is
     * present — this method returns {@code null}, and the caller falls through to the
     * standard annotation-name heuristic.
     *
     * @return the HTTP method string (e.g. {@code "POST"}), {@code "*"} when the method
     *         attribute is absent, or {@code null} if parsing does not apply
     */
    private String parseRequestMappingMethod(AnnotationInfo ann) {
        if (!"RequestMapping".equals(ann.getAnnotationName())) {
            return null;
        }
        String params = ann.getAnnotationParams();
        if (params == null || params.equals("{}")) {
            return "*";
        }
        // Match "method":"VALUE" where VALUE may be "RequestMethod.POST", "POST",
        // or "{RequestMethod.POST,RequestMethod.GET}".
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"method\":\\s*\"\\{?([^\"}]+)\"?")
            .matcher(params);
        if (!m.find()) {
            return "*";  // @RequestMapping without explicit method → any
        }
        String raw = m.group(1);
        // Handle composite: extract first method from "RequestMethod.POST,RequestMethod.GET"
        String first = raw.contains(",") ? raw.substring(0, raw.indexOf(',')).trim() : raw.trim();
        // Strip "RequestMethod." prefix if present
        if (first.startsWith("RequestMethod.")) {
            first = first.substring("RequestMethod.".length());
        }
        return first.isBlank() ? "*" : first;
    }

    private String parseAnnotationPath(String params) {
        if (params == null || params.equals("{}")) return "";
        // stored as {"value":"\"check\""} — strip escaped quotes
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"(?:value|path)\":\\s*\"\\\\?\"?([^\"\\\\]+)")
            .matcher(params);
        return m.find() ? m.group(1) : "";
    }

    /** Combines a class-level path prefix with a method-level path segment. */
    private String combinePaths(String prefix, String methodPath) {
        if (prefix == null || prefix.isBlank()) return methodPath;
        if (methodPath == null || methodPath.isBlank()) return prefix;
        String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String m = methodPath.startsWith("/") ? methodPath : "/" + methodPath;
        return p + m;
    }
}
