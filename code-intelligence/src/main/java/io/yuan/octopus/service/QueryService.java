package io.yuan.octopus.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.dto.response.*;
import io.yuan.octopus.dto.response.AnalysisReportDTO.*;
import io.yuan.octopus.dto.response.CallChainDTO.CallChainNodeDTO;
import io.yuan.octopus.entity.*;
import io.yuan.octopus.exception.ResourceNotFoundException;
import io.yuan.octopus.exception.ValidationException;
import io.yuan.octopus.repository.*;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private static final String ENTRY_TYPE_HTTP_API = "HTTP_API";
    private static final String ENTRY_TYPE_SCHEDULED = "SCHEDULED";
    private static final String ENTRY_TYPE_MESSAGE_LISTENER = "MESSAGE_LISTENER";
    private static final String ENTRY_TYPE_ROOT_METHOD = "ROOT_METHOD";

    private static final List<String> HTTP_METHOD_ANNOTATIONS = List.of(
        "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping",
        // Go 合成注解（go-project-analyzer 的 HTTP 路由识别），params 携带 path/method
        "HttpRoute"
    );
    private static final List<String> MESSAGE_LISTENER_ANNOTATIONS = List.of(
        "EventListener", "KafkaListener", "RabbitListener", "JmsListener", "SqsListener", "RocketMQMessageListener"
    );

    private final Driver neo4jDriver;
    private final ClassInfoRepository classInfoRepository;
    private final MethodInfoRepository methodInfoRepository;
    private final FieldInfoRepository fieldInfoRepository;
    private final AnnotationInfoRepository annotationInfoRepository;
    private final InheritanceRelationRepository inheritanceRelationRepository;
    private final ClassDependencyRepository classDependencyRepository;
    private final ClassInterfaceRelationRepository classInterfaceRelationRepository;
    private final AnalyzedProjectRepository javaProjectRepository;
    private final GitService gitService;
    private final RepoConfigService repoConfigService;
    private final Gson gson = new Gson();

    /** branches/commits 列表缓存 TTL：这两个查询底层是远端 git fetch，开销大且变化不频繁。 */
    private static final long GIT_LIST_CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private final ConcurrentHashMap<Long, CachedGitList<List<BranchInfo>>> branchCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedGitList<List<CommitInfo>>> commitCache = new ConcurrentHashMap<>();

    private record CachedGitList<T>(T value, long expiresAt) {}

    public QueryService(Driver neo4jDriver,
                        ClassInfoRepository classInfoRepository,
                        MethodInfoRepository methodInfoRepository,
                        FieldInfoRepository fieldInfoRepository,
                        AnnotationInfoRepository annotationInfoRepository,
                        InheritanceRelationRepository inheritanceRelationRepository,
                        ClassDependencyRepository classDependencyRepository,
                        ClassInterfaceRelationRepository classInterfaceRelationRepository,
                        AnalyzedProjectRepository javaProjectRepository,
                        GitService gitService,
                        RepoConfigService repoConfigService) {
        this.neo4jDriver = neo4jDriver;
        this.classInfoRepository = classInfoRepository;
        this.methodInfoRepository = methodInfoRepository;
        this.fieldInfoRepository = fieldInfoRepository;
        this.annotationInfoRepository = annotationInfoRepository;
        this.inheritanceRelationRepository = inheritanceRelationRepository;
        this.classDependencyRepository = classDependencyRepository;
        this.classInterfaceRelationRepository = classInterfaceRelationRepository;
        this.javaProjectRepository = javaProjectRepository;
        this.gitService = gitService;
        this.repoConfigService = repoConfigService;
    }

    // ==================== Neo4j Query Methods ====================

    /**
     * 构造 Neo4j 版本过滤片段（对应 BR-DIFF-011）：
     * commitId 为空 → 取 HEAD（current=true 或缺失）；commitId 非空 → 精确匹配 commitId。
     */
    private String versionFilter(String nodeAlias, String commitId) {
        if (commitId == null || commitId.isBlank()) {
            return "coalesce(" + nodeAlias + ".current, true) = true";
        }
        return nodeAlias + ".commitId = $commitId";
    }

    /**
     * 判断是否为版本化查询（commitId 非空）。
     */
    private boolean isVersioned(String commitId) {
        return commitId != null && !commitId.isBlank();
    }

    /**
     * 构造 branch 过滤片段：branch 为空时不限制（跨分支），
     * 非空时限定节点 branch 属性精确匹配。
     */
    private String branchFilter(String nodeAlias, String branch) {
        if (branch == null || branch.isBlank()) {
            return "true";
        }
        return nodeAlias + ".branch = $branch";
    }

    private boolean hasBranch(String branch) {
        return branch != null && !branch.isBlank();
    }

    public List<MethodEdgeDTO> getCallers(String qualifiedClassName, String methodName,
                                          List<String> paramTypes, String branch, String commitId) {
        String callerFilter = versionFilter("caller", commitId);
        String calleeFilter = versionFilter("callee", commitId);
        String cypher = """
            MATCH (caller:MethodNode)-[c:CALLS]->(callee:MethodNode)
            WHERE callee.qualifiedClassName = $className AND callee.methodName = $methodName
              AND %s AND %s AND %s AND %s
            RETURN caller, c.callType AS callType, c.lineNumber AS lineNumber
            """.formatted(callerFilter, calleeFilter, branchFilter("caller", branch), branchFilter("callee", branch));
        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("className", qualifiedClassName);
            params.put("methodName", methodName);
            if (isVersioned(commitId)) params.put("commitId", commitId);
            if (hasBranch(branch)) params.put("branch", branch);
            Result result = session.run(cypher, params);
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

    public List<MethodEdgeDTO> getCallees(String qualifiedClassName, String methodName,
                                          List<String> paramTypes, String branch, String commitId) {
        String callerFilter = versionFilter("caller", commitId);
        String calleeFilter = versionFilter("callee", commitId);
        String cypher = """
            MATCH (caller:MethodNode)-[c:CALLS]->(callee:MethodNode)
            WHERE caller.qualifiedClassName = $className AND caller.methodName = $methodName
              AND %s AND %s AND %s AND %s
            RETURN callee, c.callType AS callType, c.lineNumber AS lineNumber
            """.formatted(callerFilter, calleeFilter, branchFilter("caller", branch), branchFilter("callee", branch));
        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("className", qualifiedClassName);
            params.put("methodName", methodName);
            if (isVersioned(commitId)) params.put("commitId", commitId);
            if (hasBranch(branch)) params.put("branch", branch);
            Result result = session.run(cypher, params);
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

    public CallChainDTO getCallChain(String qualifiedClassName, String methodName,
                                     List<String> paramTypes, int maxDepth,
                                     String branch, String commitId) {
        // maxDepth <= 0 means unlimited depth
        String depthSpec = maxDepth > 0 ? "1.." + maxDepth : "1..";
        // Exclude SPRING_DI edges: they represent class-level DI dependencies, not method invocations.
        // Including them would produce paths that terminate at virtual class nodes (not real methods),
        // making the result a mixed dependency graph rather than a pure directed method-call graph.
        String nodeFilter = "(" + branchFilter("n", branch) + " AND " + versionFilter("n", commitId) + ")";
        String cypher = String.format("""
            MATCH path = (start:MethodNode)-[:CALLS*%s]->(end:MethodNode)
            WHERE start.qualifiedClassName = $className AND start.methodName = $methodName
              AND ALL(r IN relationships(path) WHERE r.callType <> 'SPRING_DI')
              AND ALL(n IN nodes(path) WHERE %s)
            RETURN path
            """, depthSpec, nodeFilter);
        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("className", qualifiedClassName);
            params.put("methodName", methodName);
            if (isVersioned(commitId)) params.put("commitId", commitId);
            if (hasBranch(branch)) params.put("branch", branch);
            Result result = session.run(cypher, params);

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
    public CallTraceDTO traceMethod(String qualifiedClassName, String methodName,
                                    List<String> paramTypes, int depth,
                                    String branch, String commitId) {
        int effectiveDepth = depth <= 0 ? 5 : depth;
        String nodeFilter = "(" + branchFilter("n", branch) + " AND " + versionFilter("n", commitId) + ")";

        // Upstream: callers that call this method (reverse direction)
        List<MethodEdgeDTO> upstream = new ArrayList<>();
        String upstreamCypher = String.format("""
            MATCH path = (caller:MethodNode)-[c:CALLS*1..%d]->(callee:MethodNode)
            WHERE callee.qualifiedClassName = $className AND callee.methodName = $methodName
              AND ALL(n IN nodes(path) WHERE %s)
              AND ALL(r IN relationships(path) WHERE r.callType <> 'SPRING_DI')
            RETURN caller, last(c).callType AS callType, last(c).lineNumber AS lineNumber
            """, effectiveDepth, nodeFilter);
        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("className", qualifiedClassName);
            params.put("methodName", methodName);
            if (isVersioned(commitId)) params.put("commitId", commitId);
            if (hasBranch(branch)) params.put("branch", branch);
            Result result = session.run(upstreamCypher, params);
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
              AND ALL(n IN nodes(path) WHERE %s)
              AND ALL(r IN relationships(path) WHERE r.callType <> 'SPRING_DI')
            RETURN callee, last(c).callType AS callType, last(c).lineNumber AS lineNumber
            """, effectiveDepth, nodeFilter);
        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("className", qualifiedClassName);
            params.put("methodName", methodName);
            if (isVersioned(commitId)) params.put("commitId", commitId);
            if (hasBranch(branch)) params.put("branch", branch);
            Result result = session.run(downstreamCypher, params);
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
        return getClassDetail(qualifiedClassName, null);
    }

    /**
     * 类详情：commitId 为空取 HEAD 版本，非空精确匹配该 commit 的版本行。
     */
    public ClassDetailDTO getClassDetail(String qualifiedClassName, String commitId) {
        // 版本化后同名行会随分析累积，按 commitId 精确取；未指定时取 HEAD 的最新一行
        List<ClassInfo> matched = (commitId != null && !commitId.isBlank())
                ? classInfoRepository.findByQualifiedNameAndCommitId(qualifiedClassName, commitId)
                : classInfoRepository.findByQualifiedNameAndIsHeadTrue(qualifiedClassName);
        ClassInfo classInfo = matched.isEmpty() ? null : matched.get(matched.size() - 1);
        if (classInfo == null) {
            throw new ResourceNotFoundException("Class not found: " + qualifiedClassName
                    + (commitId != null && !commitId.isBlank() ? " at commit " + commitId : ""));
        }

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
        List<ClassInfo> classes = classInfoRepository.findByQualifiedNameStartingWithAndIsHeadTrue(packageName);
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
            .map(r -> {
                List<ClassInfo> headClasses = classInfoRepository.findByQualifiedNameAndIsHeadTrue(r.getChildQualifiedName());
                return headClasses.isEmpty() ? null : headClasses.get(headClasses.size() - 1);
            })
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
        List<AnnotationInfo> annotations = annotationInfoRepository.findByAnnotationNameAndIsHeadTrue(annotationName);
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

    public List<EndpointDTO> getEndpoints(Long projectId, String branch, String commitId) {
        return getEntryPoints(projectId, ENTRY_TYPE_HTTP_API, branch, commitId).stream()
                .map(entry -> new EndpointDTO(
                        entry.httpMethod(),
                        entry.path(),
                        entry.methodName(),
                        entry.qualifiedClassName(),
                        entry.ownerSimpleName(),
                        entry.signature(),
                        entry.projectId(),
                        entry.projectName(),
                        entry.branch(),
                        entry.commitId()))
                .toList();
    }

    public List<EntryPointDTO> getEntryPoints(Long projectId, String type, String branch, String commitId) {
        String normalizedType = normalizeEntryPointType(type);
        EntryPointContext context = buildEntryPointContext(projectId, branch, commitId);
        Map<String, EntryPointDTO> result = new LinkedHashMap<>();

        if (normalizedType == null || ENTRY_TYPE_HTTP_API.equals(normalizedType)) {
            addHttpEntryPoints(context, result);
        }
        if (normalizedType == null || ENTRY_TYPE_MESSAGE_LISTENER.equals(normalizedType)) {
            addAnnotatedEntryPoints(context, MESSAGE_LISTENER_ANNOTATIONS, ENTRY_TYPE_MESSAGE_LISTENER, result);
        }
        if (normalizedType == null || ENTRY_TYPE_SCHEDULED.equals(normalizedType)) {
            addAnnotatedEntryPoints(context, List.of("Scheduled"), ENTRY_TYPE_SCHEDULED, result);
        }
        if (ENTRY_TYPE_ROOT_METHOD.equals(normalizedType)) {
            addRootMethodEntryPoints(projectId, result);
        } else if (normalizedType == null && result.isEmpty()) {
            // 注解型入口为空（Go 等无注解语言的项目）时回退到根方法，
            // 让"按项目浏览入口"对非注解语言也可用；Java 项目保持 API 优先不变
            addRootMethodEntryPoints(projectId, result);
        }

        return result.values().stream()
                .sorted(Comparator
                        .comparingInt((EntryPointDTO e) -> entryTypeRank(e.entryType()))
                        .thenComparing(e -> e.ownerSimpleName() != null ? e.ownerSimpleName() : "")
                        .thenComparing(e -> e.methodName() != null ? e.methodName() : "")
                        .thenComparing(e -> e.displayName() != null ? e.displayName() : ""))
                .toList();
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

    /**
     * 列出某项目（已分析）关联的 Git 仓库的所有分支。
     * 通过 projectId → AnalyzedProjectEntity.repoConfigId → RepoConfig → JGit 解析。
     * 本地分析项目（repoConfigId 为 null）返回空列表。
     * <p>
     * 结果缓存 5 分钟：该查询每次都会触发远端 fetchAll（网络开销大），
     * 而分支列表变化频率低，前端下拉会高频重复请求。
     */
    public List<BranchInfo> listBranches(Long projectId) {
        AnalyzedProjectEntity project = javaProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在: " + projectId));
        if (project.getRepoConfigId() == null) {
            return List.of();
        }
        CachedGitList<List<BranchInfo>> cached = branchCache.get(projectId);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.value();
        }
        RepoConfig repoConfig = repoConfigService.getById(project.getRepoConfigId());
        List<BranchInfo> branches = gitService.listBranches(repoConfig);
        branchCache.put(projectId, new CachedGitList<>(branches, System.currentTimeMillis() + GIT_LIST_CACHE_TTL_MILLIS));
        return branches;
    }

    /**
     * 列出某项目某分支最近的 commit 历史（结果缓存 5 分钟，理由同 {@link #listBranches}）。
     */
    public List<CommitInfo> listCommits(Long projectId, String branch, int limit) {
        AnalyzedProjectEntity project = javaProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在: " + projectId));
        if (project.getRepoConfigId() == null) {
            return List.of();
        }
        String cacheKey = projectId + ":" + branch;
        CachedGitList<List<CommitInfo>> cached = commitCache.get(cacheKey);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.value();
        }
        RepoConfig repoConfig = repoConfigService.getById(project.getRepoConfigId());
        List<CommitInfo> commits = gitService.listCommits(repoConfig, branch, limit);
        commitCache.put(cacheKey, new CachedGitList<>(commits, System.currentTimeMillis() + GIT_LIST_CACHE_TTL_MILLIS));
        return commits;
    }

    // -------------------------------------------------------------------------
    // Metadata-driven version enumeration
    // -------------------------------------------------------------------------

    /**
     * 从元数据枚举项目已分析过的分支（按最近分析排序）。
     * <p>
     * 本地项目（repoConfigId 为 null）没有 Git 服务可询，版本选择的唯一事实来源
     * 是已落库的 class_info.branch；同一本地仓库多分支分析后数据按 branch 列共存
     * 于同一个 project。对 repo 驱动项目同样可用（列出历史分析过的分支）。
     */
    public List<BranchInfo> getAnalyzedBranches(Long projectId) {
        javaProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在: " + projectId));
        return classInfoRepository.findAnalyzedBranches(projectId).stream()
                .map(branch -> {
                    List<String> heads = classInfoRepository.findHeadCommitIds(projectId, branch);
                    return new BranchInfo(branch, heads.isEmpty() ? null : heads.get(0));
                })
                .toList();
    }

    /** 从元数据枚举指定分支已分析过的 commit（按最近分析排序）。 */
    public List<CommitInfo> getAnalyzedCommits(Long projectId, String branch) {
        javaProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在: " + projectId));
        return classInfoRepository.findAnalyzedCommitIds(projectId, branch).stream()
                .map(commitId -> new CommitInfo(commitId, "已分析版本", "", 0L))
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
        List<AnalyzedProjectEntity> projects = projectNameFilter != null && !projectNameFilter.isBlank()
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

    private List<MetadataStats> buildMetadataStats(List<AnalyzedProjectEntity> projects) {
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
                methodCount = methodInfoRepository.findByClassInfoIdIn(classIds).size();
                fieldCount = fieldInfoRepository.findByClassIdIn(classIds).size();
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
        mysqlClasses = classInfoRepository.findByIsHeadTrue().stream()
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
                                   CrossValidation crossValidation, List<AnalyzedProjectEntity> projects) {
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

    private String normalizeEntryPointType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case ENTRY_TYPE_HTTP_API, ENTRY_TYPE_SCHEDULED, ENTRY_TYPE_MESSAGE_LISTENER, ENTRY_TYPE_ROOT_METHOD -> normalized;
            default -> throw new ValidationException("Unsupported entry-point type: " + type);
        };
    }

    private EntryPointContext buildEntryPointContext(Long projectId, String branch, String commitId) {
        // 版本过滤（对应 BR-DIFF-011）：
        //   commitId 非空 → 精确匹配该 commit 的版本数据
        //   commitId 为空 → 取 is_head=true 的 HEAD 版本
        boolean versioned = commitId != null && !commitId.isBlank();
        boolean hasBranch = branch != null && !branch.isBlank();
        List<ClassInfo> classes;
        if (projectId != null) {
            if (versioned && hasBranch) {
                classes = classInfoRepository.findByProjectIdAndBranchAndCommitId(projectId, branch, commitId);
            } else if (versioned) {
                classes = classInfoRepository.findByProjectIdAndCommitId(projectId, commitId);
            } else if (hasBranch) {
                classes = classInfoRepository.findByProjectIdAndBranchAndIsHeadTrue(projectId, branch);
            } else {
                classes = classInfoRepository.findByProjectIdAndIsHeadTrue(projectId);
            }
        } else {
            // 无 projectId 的全表入口查询：版本化保留后全表行数持续增长，
            // 必须在 SQL 层过滤而不是 findAll 载入内存再筛
            classes = versioned
                    ? classInfoRepository.findByCommitId(commitId)
                    : classInfoRepository.findByIsHeadTrue();
        }
        if (classes.isEmpty()) {
            return new EntryPointContext(Map.of(), Map.of(), Map.of(), Map.of(), List.of());
        }

        Map<Long, ClassInfo> classById = classes.stream()
                .collect(Collectors.toMap(ClassInfo::getId, value -> value));
        List<Long> classIds = new ArrayList<>(classById.keySet());
        List<MethodInfo> methods = methodInfoRepository.findByClassInfoIdIn(classIds);
        Map<Long, MethodInfo> methodById = methods.stream()
                .collect(Collectors.toMap(MethodInfo::getId, value -> value));
        List<Long> methodIds = methods.stream().map(MethodInfo::getId).toList();

        Map<Long, String> classPrefixById = new HashMap<>();
        if (!classIds.isEmpty()) {
            annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                    List.of("RequestMapping"), "CLASS", classIds)
                    .forEach(annotation -> classPrefixById.put(
                            annotation.getTargetId(), parseAnnotationPath(annotation.getAnnotationParams())));
        }

        Map<Long, AnalyzedProjectEntity> projectByClassId = new HashMap<>();
        Set<Long> projectIds = classes.stream()
                .map(ClassInfo::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!projectIds.isEmpty()) {
            Map<Long, AnalyzedProjectEntity> projectById = new HashMap<>();
            javaProjectRepository.findAllById(projectIds).forEach(project -> projectById.put(project.getId(), project));
            classes.forEach(classInfo -> {
                if (classInfo.getProjectId() != null) {
                    AnalyzedProjectEntity project = projectById.get(classInfo.getProjectId());
                    if (project != null) {
                        projectByClassId.put(classInfo.getId(), project);
                    }
                }
            });
        }
        return new EntryPointContext(classById, methodById, projectByClassId, classPrefixById, methodIds);
    }

    private void addHttpEntryPoints(EntryPointContext context, Map<String, EntryPointDTO> result) {
        List<AnnotationInfo> annotations = findMethodAnnotations(context.methodIds(), HTTP_METHOD_ANNOTATIONS);
        for (AnnotationInfo annotation : annotations) {
            MethodInfo methodInfo = context.methodById().get(annotation.getTargetId());
            if (methodInfo == null || methodInfo.getClassInfo() == null) {
                continue;
            }
            ClassInfo classInfo = methodInfo.getClassInfo();
            String httpMethod = parseRequestMappingMethod(annotation);
            if (httpMethod == null && "HttpRoute".equals(annotation.getAnnotationName())) {
                // Go 合成注解：params = {"path":"/api/x","method":"GET"}
                httpMethod = parseStringParam(annotation.getAnnotationParams(), "method");
                if (httpMethod == null || httpMethod.isBlank()) {
                    httpMethod = "*";
                }
            }
            if (httpMethod == null) {
                httpMethod = annotation.getAnnotationName().replace("Mapping", "").toUpperCase(Locale.ROOT);
                if ("REQUEST".equals(httpMethod)) {
                    httpMethod = "*";
                }
            }
            String methodPath = parseAnnotationPath(annotation.getAnnotationParams());
            String classPrefix = context.classPrefixById().getOrDefault(classInfo.getId(), "");
            String fullPath = combinePaths(classPrefix, methodPath);
            EntryPointDTO entryPoint = buildEntryPoint(
                    ENTRY_TYPE_HTTP_API,
                    httpMethod + " " + (fullPath == null || fullPath.isBlank() ? "/" : fullPath),
                    fullPath == null || fullPath.isBlank() ? "/" : fullPath,
                    httpMethod,
                    fullPath,
                    methodInfo,
                    classInfo,
                    context.projectByClassId().get(classInfo.getId()));
            result.putIfAbsent(entryPointKey(entryPoint.signature(), entryPoint.entryType()), entryPoint);
        }
    }

    private void addAnnotatedEntryPoints(EntryPointContext context, List<String> annotationNames,
                                         String entryType, Map<String, EntryPointDTO> result) {
        List<AnnotationInfo> annotations = findMethodAnnotations(context.methodIds(), annotationNames);
        for (AnnotationInfo annotation : annotations) {
            MethodInfo methodInfo = context.methodById().get(annotation.getTargetId());
            if (methodInfo == null || methodInfo.getClassInfo() == null) {
                continue;
            }
            ClassInfo classInfo = methodInfo.getClassInfo();
            String triggerLabel = switch (entryType) {
                case ENTRY_TYPE_SCHEDULED -> parseScheduledTrigger(annotation.getAnnotationParams());
                case ENTRY_TYPE_MESSAGE_LISTENER -> parseMessageTrigger(annotation.getAnnotationName(), annotation.getAnnotationParams());
                default -> annotation.getAnnotationName();
            };
            EntryPointDTO entryPoint = buildEntryPoint(
                    entryType,
                    buildDisplayName(entryType, annotation.getAnnotationName(), triggerLabel, classInfo.getSimpleName(), methodInfo.getMethodName()),
                    triggerLabel,
                    null,
                    null,
                    methodInfo,
                    classInfo,
                    context.projectByClassId().get(classInfo.getId()));
            result.putIfAbsent(entryPointKey(entryPoint.signature(), entryPoint.entryType()), entryPoint);
        }
    }

    private void addRootMethodEntryPoints(Long projectId, Map<String, EntryPointDTO> result) {
        String cypher = """
            MATCH (entry:MethodNode)
            WHERE coalesce(entry.current, true) = true
              AND ($projectId = -1 OR entry.projectId = $projectId)
              AND NOT ()-[:CALLS]->(entry)
              AND coalesce(entry.qualifiedClassName, '') <> 'org.springframework.scheduling.Scheduler'
              AND NOT (
                  entry.returnType = 'void'
                  AND coalesce(entry.modifiers, '') = ''
                  AND entry.methodName = split(entry.qualifiedClassName, '.')[-1]
              )
            RETURN entry
            """;
        Map<String, Object> params = Map.of("projectId", projectId != null ? projectId : -1L);
        try (Session session = neo4jDriver.session()) {
            Result neo4jResult = session.run(cypher, params);
            while (neo4jResult.hasNext()) {
                Record record = neo4jResult.next();
                Node node = record.get("entry").asNode();
                String signature = nullableStringProperty(node, "signature");
                if (signature == null || result.containsKey(entryPointKey(signature, ENTRY_TYPE_ROOT_METHOD))
                        || containsSignature(result, signature)) {
                    continue;
                }
                String qualifiedClassName = nullableStringProperty(node, "qualifiedClassName");
                String methodName = nullableStringProperty(node, "methodName");
                String simpleName = extractSimpleName(qualifiedClassName);
                EntryPointDTO entryPoint = new EntryPointDTO(
                        ENTRY_TYPE_ROOT_METHOD,
                        (simpleName != null ? simpleName : "?") + "#" + (methodName != null ? methodName : "?"),
                        "No incoming call edge",
                        null,
                        null,
                        methodName,
                        qualifiedClassName,
                        simpleName,
                        null,
                        signature,
                        node.containsKey("projectId") && !node.get("projectId").isNull() ? node.get("projectId").asLong() : null,
                        nullableStringProperty(node, "projectName"),
                        nullableStringProperty(node, "branch"),
                        nullableStringProperty(node, "commitId"));
                result.put(entryPointKey(signature, entryPoint.entryType()), entryPoint);
            }
        }
    }

    private boolean containsSignature(Map<String, EntryPointDTO> result, String signature) {
        return result.values().stream().anyMatch(entryPoint -> Objects.equals(signature, entryPoint.signature()));
    }

    private List<AnnotationInfo> findMethodAnnotations(List<Long> methodIds, List<String> annotationNames) {
        if (methodIds.isEmpty()) {
            return List.of();
        }
        return annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                annotationNames, "METHOD", methodIds);
    }

    private EntryPointDTO buildEntryPoint(String entryType, String displayName, String triggerLabel,
                                          String httpMethod, String path, MethodInfo methodInfo,
                                          ClassInfo classInfo, AnalyzedProjectEntity project) {
        String ownerSimpleName = classInfo.getSimpleName() != null
                ? classInfo.getSimpleName()
                : extractSimpleName(classInfo.getQualifiedName());
        return new EntryPointDTO(
                entryType,
                displayName,
                triggerLabel,
                httpMethod,
                path,
                methodInfo.getMethodName(),
                classInfo.getQualifiedName(),
                ownerSimpleName,
                ENTRY_TYPE_HTTP_API.equals(entryType) ? ownerSimpleName : null,
                methodInfo.getMethodSignature(),
                project != null ? project.getId() : null,
                project != null ? project.getProjectName() : null,
                project != null ? project.getBranch() : null,
                project != null ? project.getCommitId() : null
        );
    }

    private String buildDisplayName(String entryType, String annotationName, String triggerLabel,
                                    String ownerSimpleName, String methodName) {
        if (ENTRY_TYPE_SCHEDULED.equals(entryType)) {
            return "@Scheduled " + triggerLabel;
        }
        if (ENTRY_TYPE_MESSAGE_LISTENER.equals(entryType)) {
            return annotationName + " " + triggerLabel;
        }
        return (ownerSimpleName != null ? ownerSimpleName : "?") + "#" + (methodName != null ? methodName : "?");
    }

    private String parseScheduledTrigger(String params) {
        return parseAnnotationAttribute(params, List.of("cron", "fixedDelayString", "fixedRateString", "fixedDelay", "fixedRate"), "@Scheduled");
    }

    private String parseMessageTrigger(String annotationName, String params) {
        return parseAnnotationAttribute(params, List.of("topics", "topic", "queues", "queue", "destination", "value"), annotationName);
    }

    private String parseAnnotationAttribute(String params, List<String> attributeNames, String fallback) {
        if (params == null || params.isBlank() || "{}".equals(params)) {
            return fallback;
        }
        try {
            Map<String, String> values = gson.fromJson(params, new TypeToken<Map<String, String>>() {}.getType());
            for (String attributeName : attributeNames) {
                String value = values.get(attributeName);
                if (value != null && !value.isBlank()) {
                    return normalizeAnnotationAttributeValue(value, fallback);
                }
            }
        } catch (Exception ignored) {
            // Fall through to the legacy regex parser for old or malformed metadata rows.
        }
        for (String attributeName : attributeNames) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"" + attributeName + "\":\\s*\"([^\"]+)\"")
                    .matcher(params);
            if (matcher.find()) {
                return matcher.group(1).replace("\\\"", "\"");
            }
        }
        return fallback;
    }

    private String normalizeAnnotationAttributeValue(String value, String fallback) {
        String normalized = value.trim()
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        normalized = stripWrappingQuotes(normalized);
        if (normalized.startsWith("{") && normalized.endsWith("}")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
            normalized = stripWrappingQuotes(normalized);
        }
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
            normalized = stripWrappingQuotes(normalized);
        }
        return normalized.isBlank() ? fallback : normalized;
    }

    private String stripWrappingQuotes(String value) {
        String stripped = value.trim();
        while (stripped.length() >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")) {
            stripped = stripped.substring(1, stripped.length() - 1).trim();
        }
        return stripped;
    }

    private int entryTypeRank(String entryType) {
        return switch (entryType) {
            case ENTRY_TYPE_HTTP_API -> 0;
            case ENTRY_TYPE_MESSAGE_LISTENER -> 1;
            case ENTRY_TYPE_SCHEDULED -> 2;
            case ENTRY_TYPE_ROOT_METHOD -> 3;
            default -> 9;
        };
    }

    private String entryPointKey(String signature, String entryType) {
        return signature + "|" + entryType;
    }

    private String extractSimpleName(String qualifiedClassName) {
        if (qualifiedClassName == null || qualifiedClassName.isBlank()) {
            return null;
        }
        int lastDot = qualifiedClassName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedClassName.substring(lastDot + 1) : qualifiedClassName;
    }

    private String nullableStringProperty(Node node, String property) {
        if (!node.containsKey(property) || node.get(property).isNull()) {
            return null;
        }
        String value = node.get(property).asString();
        return value.isBlank() ? null : value;
    }

    private String parseAnnotationPath(String params) {
        return parseStringParam(params, null);
    }

    /** Parses a string param from the stored annotation params JSON; keys is a
     *  set of alternative keys ("value"/"path" for paths), or a single key. */
    private String parseStringParam(String params, String key) {
        if (params == null || params.equals("{}")) return "";
        String keyPattern = key != null ? java.util.regex.Pattern.quote(key) : "(?:value|path)";
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + keyPattern + "\":\\s*\"\\\\?\"?([^\"\\\\]+)")
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

    private record EntryPointContext(
            Map<Long, ClassInfo> classById,
            Map<Long, MethodInfo> methodById,
            Map<Long, AnalyzedProjectEntity> projectByClassId,
            Map<Long, String> classPrefixById,
            List<Long> methodIds
    ) {}
}
