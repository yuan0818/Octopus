package io.yuan.octopus.service;

import io.yuan.octopus.dto.request.SourcePathRequest;
import io.yuan.octopus.dto.response.CodePathDTO;
import io.yuan.octopus.dto.response.CodePathDTO.CodePathStepDTO;
import io.yuan.octopus.dto.response.EndpointDiffPathsDTO;
import io.yuan.octopus.dto.response.EntryChainDiffDTO;
import io.yuan.octopus.dto.response.EntryChainDiffDTO.ChangedMethod;
import io.yuan.octopus.dto.response.EntryChainDiffDTO.MethodRef;
import io.yuan.octopus.dto.response.ImpactPathsDTO;
import io.yuan.octopus.dto.response.MethodNodeDTO;
import io.yuan.octopus.dto.response.SourcePathDTO;
import io.yuan.octopus.exception.ResourceNotFoundException;
import io.yuan.octopus.exception.ValidationException;
import io.yuan.octopus.repository.AnalyzedProjectRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PathQueryService {

    private static final int DEFAULT_MAX_DEPTH = 12;
    private static final int MAX_DEPTH = 30;
    private static final int DEFAULT_MAX_PATHS = 500;
    private static final int MAX_PATHS = 2000;

    private final Driver neo4jDriver;
    private final AnalyzedProjectRepository javaProjectRepository;
    private final GitMethodSourceClient gitSourceClient;
    private final io.yuan.octopus.repository.ClassInfoRepository classInfoRepository;
    private final io.yuan.octopus.repository.MethodInfoRepository methodInfoRepository;

    /** 链路成员 diff 的安全上限：超过则截断并置 truncated 标志。 */
    private static final int MAX_CHAIN_MEMBERS = 3000;

    public PathQueryService(Driver neo4jDriver, AnalyzedProjectRepository javaProjectRepository,
                            GitMethodSourceClient gitSourceClient,
                            io.yuan.octopus.repository.ClassInfoRepository classInfoRepository,
                            io.yuan.octopus.repository.MethodInfoRepository methodInfoRepository) {
        this.neo4jDriver = neo4jDriver;
        this.javaProjectRepository = javaProjectRepository;
        this.gitSourceClient = gitSourceClient;
        this.classInfoRepository = classInfoRepository;
        this.methodInfoRepository = methodInfoRepository;
    }

    // -------------------------------------------------------------------------
    // Entry-chain version diff (L1: chain members + source comparison)
    // -------------------------------------------------------------------------

    /**
     * 枚举同一入口在两个版本（(branch, commitId) 对）的下游链路成员集合，
     * 输出新增/移除/源码变更的方法。
     * <p>
     * 源码文本按 git-only 原则获取：以 method_info 中的坐标
     * （file_path + line_start/line_end）经 code-reconstruct 从该 commit 的
     * git blob 切片；git 不可达（无 git 的本地项目 / 服务不可用）时回退
     * MethodNode 上分析时的 sourceCode 快照，并以 gitBacked=false 标注。
     * <p>
     * 入口在某一版本不存在时，该版本成员集合为空（整个链路表现为
     * added/removed），两个版本都不存在才报错。
     */
    public EntryChainDiffDTO getEntryChainDiff(Long projectId, String className, String methodName,
                                               String fromBranch, String fromCommitId,
                                               String toBranch, String toCommitId,
                                               Integer maxDepth) {
        if (className == null || className.isBlank() || methodName == null || methodName.isBlank()) {
            throw new ValidationException("className and methodName must not be blank");
        }
        int depthLimit = normalizeMaxDepth(maxDepth);

        ChainMembers from = collectChainMembers(projectId, className, methodName, fromBranch, fromCommitId, depthLimit);
        ChainMembers to = collectChainMembers(projectId, className, methodName, toBranch, toCommitId, depthLimit);
        if (from.members().isEmpty() && to.members().isEmpty()) {
            throw new ResourceNotFoundException(
                    "Entry not found in either version: " + className + "#" + methodName);
        }

        List<MethodRef> added = to.members().keySet().stream()
                .filter(sig -> !from.members().containsKey(sig))
                .map(sig -> to.members().get(sig).toRef())
                .toList();
        List<MethodRef> removed = from.members().keySet().stream()
                .filter(sig -> !to.members().containsKey(sig))
                .map(sig -> from.members().get(sig).toRef())
                .toList();

        // 共同成员：git 文本优先，快照回退
        Map<String, String> fromGit = gitTexts(projectId, fromBranch, fromCommitId, from);
        Map<String, String> toGit = gitTexts(projectId, toBranch, toCommitId, to);
        boolean gitBacked = !fromGit.isEmpty() || !toGit.isEmpty();

        List<ChangedMethod> changed = new ArrayList<>();
        int unchanged = 0;
        for (Map.Entry<String, ChainMember> entry : to.members().entrySet()) {
            ChainMember fromMember = from.members().get(entry.getKey());
            if (fromMember == null) {
                continue;
            }
            String fromText = firstNonNull(fromGit.get(entry.getKey()), fromMember.sourceCode());
            String toText = firstNonNull(toGit.get(entry.getKey()), entry.getValue().sourceCode());
            if (java.util.Objects.equals(fromText, toText)) {
                unchanged++;
            } else {
                changed.add(new ChangedMethod(entry.getValue().toRef(), fromText, toText));
            }
        }

        MethodRef anchor = to.anchor() != null ? to.anchor() : from.anchor();
        return new EntryChainDiffDTO(
                anchor,
                new EntryChainDiffDTO.VersionRef(fromBranch, blankToNull(fromCommitId)),
                new EntryChainDiffDTO.VersionRef(toBranch, blankToNull(toCommitId)),
                added, removed, changed, unchanged,
                from.truncated() || to.truncated(),
                gitBacked);
    }

    /** 从 method_info 坐标批量取 git 文本；无坐标/不可达的条目缺省为 null。 */
    private Map<String, String> gitTexts(Long projectId, String branch, String commitId, ChainMembers members) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> signatures = new ArrayList<>();
        List<GitMethodSourceClient.SourceItem> items = new ArrayList<>();
        List<String> commitIds = new ArrayList<>();
        for (ChainMember member : members.members().values()) {
            MethodCoordinate coord = findCoordinate(projectId, branch, commitId,
                    member.qualifiedClassName(), member.signature());
            if (coord == null) {
                continue;
            }
            signatures.add(member.signature());
            items.add(new GitMethodSourceClient.SourceItem(coord.filePath(), coord.lineStart(), coord.lineEnd()));
            commitIds.add(coord.commitId());
        }
        if (items.isEmpty()) {
            return out;
        }
        // 同一 commit 聚批（链路成员通常同版本）
        Map<String, List<Integer>> byCommit = new LinkedHashMap<>();
        for (int i = 0; i < commitIds.size(); i++) {
            byCommit.computeIfAbsent(commitIds.get(i), k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<String, List<Integer>> batch : byCommit.entrySet()) {
            List<GitMethodSourceClient.SourceItem> batchItems = batch.getValue().stream().map(items::get).toList();
            List<String> texts = gitSourceClient.fetchBatch(projectId, batch.getKey(), batchItems);
            if (texts == null) {
                continue;
            }
            List<Integer> indexes = batch.getValue();
            for (int i = 0; i < indexes.size() && i < texts.size(); i++) {
                if (texts.get(i) != null) {
                    out.put(signatures.get(indexes.get(i)), texts.get(i));
                }
            }
        }
        return out;
    }

    private record MethodCoordinate(String filePath, int lineStart, int lineEnd, String commitId) {}

    /**
     * 类行（版本感知：commitId 非空按 commit 精确匹配；为空按 branch 的 HEAD 行，
     * 多分支并存时各分支各有 HEAD 行，必须按 branch 过滤）→ 方法坐标。
     * 找不到返回 null（消费方回退快照）。
     */
    private MethodCoordinate findCoordinate(Long projectId, String branch, String commitId,
                                            String qualifiedName, String signature) {
        List<io.yuan.octopus.entity.ClassInfo> classes;
        if (commitId != null && !commitId.isBlank()) {
            classes = classInfoRepository.findByProjectIdAndQualifiedNameAndCommitId(projectId, qualifiedName, commitId);
        } else {
            classes = classInfoRepository
                    .findByProjectIdAndQualifiedNameAndIsHeadTrue(projectId, qualifiedName).stream()
                    .filter(c -> branch == null || branch.isBlank() || branch.equals(c.getBranch()))
                    .toList();
        }
        io.yuan.octopus.entity.ClassInfo match = classes.stream().findFirst().orElse(null);
        if (match == null) {
            return null;
        }
        return methodInfoRepository.findByClassInfoIdAndMethodSignature(match.getId(), signature)
                .filter(m -> m.getLineStart() != null && m.getLineStart() > 0
                        && m.getFilePath() != null && !m.getFilePath().isBlank())
                .map(m -> new MethodCoordinate(m.getFilePath(), m.getLineStart(),
                        m.getLineEnd() != null ? m.getLineEnd() : m.getLineStart(),
                        match.getCommitId()))
                .orElse(null);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private record ChainMember(String signature, String qualifiedClassName, String methodName, String sourceCode) {
        MethodRef toRef() {
            return new MethodRef(signature, qualifiedClassName, methodName);
        }
    }

    private record ChainMembers(Map<String, ChainMember> members, MethodRef anchor, boolean truncated) {}

    /**
     * 收集入口在指定版本的下游可达成员（含入口自身），版本过滤逐节点应用
     * （与 getCallChain 一致：commitId 为空取 current=true 的 HEAD 快照）。
     */
    private ChainMembers collectChainMembers(Long projectId, String className, String methodName,
                                             String branch, String commitId, int depthLimit) {
        String versionFilter = nodeVersionFilter("n");
        String cypher = """
                MATCH (start:MethodNode)
                WHERE start.qualifiedClassName = $className
                  AND start.methodName = $methodName
                  AND %s
                WITH DISTINCT start LIMIT 1
                MATCH path = (start)-[:CALLS*0..%d]->(m:MethodNode)
                WHERE ALL(n IN nodes(path) WHERE %s)
                  AND ALL(r IN relationships(path) WHERE coalesce(r.callType, 'DIRECT') <> 'SPRING_DI')
                RETURN DISTINCT m
                """.formatted(nodeVersionFilter("start"), depthLimit, versionFilter);

        Map<String, Object> params = scopeParameters(branch, commitId);
        params.put("projectId", projectId != null ? projectId : -1L);
        params.put("className", className);
        params.put("methodName", methodName);

        Map<String, ChainMember> members = new LinkedHashMap<>();
        MethodRef anchor = null;
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                Node node = result.next().get("m").asNode();
                ChainMember member = new ChainMember(
                        stringProperty(node, "signature", null),
                        stringProperty(node, "qualifiedClassName", null),
                        stringProperty(node, "methodName", null),
                        stringProperty(node, "sourceCode", null));
                if (member.signature() == null) {
                    continue;
                }
                if (anchor == null && member.qualifiedClassName() != null
                        && member.qualifiedClassName().equals(className)
                        && methodName.equals(member.methodName())) {
                    anchor = member.toRef();
                }
                members.putIfAbsent(member.signature(), member);
                if (members.size() > MAX_CHAIN_MEMBERS) {
                    return new ChainMembers(members, anchor, true);
                }
            }
        }
        return new ChainMembers(members, anchor, false);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 逐节点版本过滤谓词（与 getCallChain 的 branch/version 语义一致）。 */
    private static String nodeVersionFilter(String var) {
        return """
                ($projectId = -1 OR %s.projectId = $projectId)
                AND ($branch = '' OR %s.branch = $branch)
                AND (($commitId = '' AND coalesce(%s.current, true) = true)
                     OR ($commitId <> '' AND %s.commitId = $commitId))""".formatted(var, var, var, var);
    }

    public ImpactPathsDTO getImpactPaths(String signature, Long projectId, String branch,
                                         String commitId, Integer maxDepth, Integer maxPaths) {
        int depthLimit = normalizeMaxDepth(maxDepth);
        int pathLimit = normalizeMaxPaths(maxPaths);
        String effectiveCommitId = resolveEffectiveCommit(signature, projectId, branch, commitId);
        GraphSnapshot snapshot = loadSnapshot(branch, effectiveCommitId);
        GraphNode anchor = resolveNode(signature, projectId, branch, effectiveCommitId);
        snapshot.nodesById().putIfAbsent(anchor.id(), anchor);

        EnumerationResult upstream = enumerate(anchor, snapshot.incoming(), true, depthLimit, pathLimit);
        EnumerationResult downstream = enumerate(anchor, snapshot.outgoing(), false, depthLimit, pathLimit);
        EnumerationResult affected = combine(upstream.paths(), downstream.paths(), pathLimit);

        return new ImpactPathsDTO(
                toMethodNodeDTO(anchor.node()),
                upstream.paths().stream().map(path -> toDTO("UPSTREAM", path)).toList(),
                downstream.paths().stream().map(path -> toDTO("DOWNSTREAM", path)).toList(),
                affected.paths().stream().map(path -> toDTO("AFFECTED", path)).toList(),
                upstream.truncated() || downstream.truncated() || affected.truncated()
        );
    }

    public EndpointDiffPathsDTO getEndpointDiffPaths(String signature, Long projectId, String branch,
                                                     String fromCommitId, String toCommitId,
                                                     Integer maxDepth, Integer maxPaths) {
        if (fromCommitId == null || fromCommitId.isBlank() || toCommitId == null || toCommitId.isBlank()) {
            throw new ValidationException("fromCommitId and toCommitId must not be blank");
        }
        int depthLimit = normalizeMaxDepth(maxDepth);
        int pathLimit = normalizeMaxPaths(maxPaths);

        String effectiveFromCommitId = resolveEffectiveCommit(signature, projectId, branch, fromCommitId);
        String effectiveToCommitId = resolveEffectiveCommit(signature, projectId, branch, toCommitId);
        EnumerationResult from = enumerateDownstream(signature, projectId, branch, effectiveFromCommitId, depthLimit, pathLimit);
        EnumerationResult to = enumerateDownstream(signature, projectId, branch, effectiveToCommitId, depthLimit, pathLimit);

        Map<String, PathRecord> fromByKey = indexPaths(from.paths());
        Map<String, PathRecord> toByKey = indexPaths(to.paths());

        List<CodePathDTO> added = toByKey.entrySet().stream()
                .filter(entry -> !fromByKey.containsKey(entry.getKey()))
                .map(entry -> toDTO("ADDED", entry.getValue()))
                .toList();
        List<CodePathDTO> removed = fromByKey.entrySet().stream()
                .filter(entry -> !toByKey.containsKey(entry.getKey()))
                .map(entry -> toDTO("REMOVED", entry.getValue()))
                .toList();
        List<CodePathDTO> unchanged = toByKey.entrySet().stream()
                .filter(entry -> fromByKey.containsKey(entry.getKey()))
                .map(entry -> toDTO("UNCHANGED", entry.getValue()))
                .toList();

        return new EndpointDiffPathsDTO(signature, fromCommitId, toCommitId,
                added, removed, unchanged, from.truncated() || to.truncated());
    }

    public SourcePathDTO getSourcePath(SourcePathRequest request) {
        String effectiveCommitId = resolveEffectiveCommit(
                request.signatures().get(0), request.projectId(), request.branch(), request.commitId());
        GraphSnapshot snapshot = loadSnapshot(request.branch(), effectiveCommitId);
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();

        for (int i = 0; i < request.signatures().size(); i++) {
            String signature = request.signatures().get(i);
            GraphNode current;
            if (i == 0) {
                current = resolveNode(signature, request.projectId(), request.branch(), effectiveCommitId);
            } else {
                GraphNode previous = nodes.get(i - 1);
                int pathIndex = i;
                GraphEdge connecting = snapshot.outgoing().getOrDefault(previous.id(), List.of()).stream()
                        .filter(edge -> signature.equals(edge.callee().signature()))
                        .findFirst()
                        .orElseThrow(() -> new ValidationException(
                                "Signatures do not form a stored path at index " + pathIndex + ": " + signature));
                edges.add(connecting);
                current = connecting.callee();
            }
            nodes.add(current);
        }

        return new SourcePathDTO(toDTO("SOURCE", new PathRecord(nodes, edges)).steps());
    }

    private EnumerationResult enumerateDownstream(String signature, Long projectId, String branch,
                                                  String commitId, int maxDepth, int maxPaths) {
        GraphSnapshot snapshot = loadSnapshot(branch, commitId);
        GraphNode anchor = resolveNode(signature, projectId, branch, commitId);
        snapshot.nodesById().putIfAbsent(anchor.id(), anchor);
        return enumerate(anchor, snapshot.outgoing(), false, maxDepth, maxPaths);
    }

    private GraphSnapshot loadSnapshot(String branch, String commitId) {
        String cypher = """
            MATCH (caller:MethodNode)-[r:CALLS]->(callee:MethodNode)
            WHERE ($branch = '' OR caller.branch = $branch)
              AND (($commitId = '' AND coalesce(caller.current, true) = true AND coalesce(callee.current, true) = true)
                   OR ($commitId <> '' AND caller.commitId = $commitId AND callee.commitId = $commitId))
              AND coalesce(r.callType, 'DIRECT') <> 'SPRING_DI'
            RETURN caller, r, callee
            """;
        Map<String, Object> params = scopeParameters(branch, commitId);
        Map<String, GraphNode> nodesById = new LinkedHashMap<>();
        Map<String, List<GraphEdge>> outgoing = new HashMap<>();
        Map<String, List<GraphEdge>> incoming = new HashMap<>();

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                Record record = result.next();
                GraphNode caller = graphNode(record.get("caller").asNode());
                GraphNode callee = graphNode(record.get("callee").asNode());
                Relationship relationship = record.get("r").asRelationship();
                GraphEdge edge = new GraphEdge(caller, callee,
                        stringProperty(relationship, "callType", "DIRECT"),
                        intProperty(relationship, "lineNumber", 0));
                nodesById.putIfAbsent(caller.id(), caller);
                nodesById.putIfAbsent(callee.id(), callee);
                outgoing.computeIfAbsent(caller.id(), ignored -> new ArrayList<>()).add(edge);
                incoming.computeIfAbsent(callee.id(), ignored -> new ArrayList<>()).add(edge);
            }
        }
        return new GraphSnapshot(nodesById, outgoing, incoming);
    }

    private GraphNode resolveNode(String signature, Long projectId, String branch, String commitId) {
        if (signature == null || signature.isBlank()) {
            throw new ValidationException("signature must not be blank");
        }
        String cypher = """
            MATCH (anchor:MethodNode {signature: $signature})
            WHERE ($projectId = -1 OR anchor.projectId = $projectId)
              AND ($branch = '' OR anchor.branch = $branch)
              AND (($commitId = '' AND coalesce(anchor.current, true) = true)
                   OR ($commitId <> '' AND anchor.commitId = $commitId))
            RETURN anchor
            LIMIT 2
            """;
        Map<String, Object> params = scopeParameters(branch, commitId);
        params.put("signature", signature);
        params.put("projectId", projectId != null ? projectId : -1L);

        try (Session session = neo4jDriver.session()) {
            List<GraphNode> matches = session.run(cypher, params)
                    .list(record -> graphNode(record.get("anchor").asNode()));
            if (matches.isEmpty()) {
                throw new ResourceNotFoundException("Method node not found for signature: " + signature);
            }
            if (matches.size() > 1) {
                throw new ValidationException("Method signature is ambiguous; provide projectId and branch: " + signature);
            }
            return matches.get(0);
        }
    }

    /**
     * An analysis with an empty Java diff intentionally reuses the prior graph snapshot.
     * In that case the project row advances to the new commit while current graph nodes keep
     * their prior commit id. Resolve requests for the new project commit to the current snapshot.
     */
    private String resolveEffectiveCommit(String signature, Long projectId, String branch, String requestedCommitId) {
        if (requestedCommitId == null || requestedCommitId.isBlank()) {
            return null;
        }
        if (nodeExists(signature, projectId, branch, requestedCommitId)) {
            return requestedCommitId;
        }
        if (projectId != null && javaProjectRepository.findById(projectId)
                .filter(project -> requestedCommitId.equals(project.getCommitId()))
                .filter(project -> branch == null || branch.isBlank() || branch.equals(project.getBranch()))
                .isPresent()
                && nodeExists(signature, projectId, branch, null)) {
            return null;
        }
        return requestedCommitId;
    }

    private boolean nodeExists(String signature, Long projectId, String branch, String commitId) {
        String cypher = """
            MATCH (anchor:MethodNode {signature: $signature})
            WHERE ($projectId = -1 OR anchor.projectId = $projectId)
              AND ($branch = '' OR anchor.branch = $branch)
              AND (($commitId = '' AND coalesce(anchor.current, true) = true)
                   OR ($commitId <> '' AND anchor.commitId = $commitId))
            RETURN count(anchor) > 0 AS present
            """;
        Map<String, Object> params = scopeParameters(branch, commitId);
        params.put("signature", signature);
        params.put("projectId", projectId != null ? projectId : -1L);
        try (Session session = neo4jDriver.session()) {
            return session.run(cypher, params).single().get("present").asBoolean();
        }
    }

    private EnumerationResult enumerate(GraphNode anchor, Map<String, List<GraphEdge>> adjacency,
                                        boolean reverse, int maxDepth, int maxPaths) {
        List<PathRecord> paths = new ArrayList<>();
        boolean[] truncated = {false};
        List<GraphNode> nodes = new ArrayList<>(List.of(anchor));
        dfs(anchor, adjacency, reverse, maxDepth, maxPaths, new HashSet<>(Set.of(anchor.id())),
                nodes, new ArrayList<>(), paths, truncated);

        if (reverse) {
            paths = paths.stream().map(this::reverse).toList();
        }
        return new EnumerationResult(paths, truncated[0]);
    }

    private void dfs(GraphNode current, Map<String, List<GraphEdge>> adjacency, boolean reverse,
                     int maxDepth, int maxPaths, Set<String> visited, List<GraphNode> nodes,
                     List<GraphEdge> edges, List<PathRecord> paths, boolean[] truncated) {
        if (paths.size() >= maxPaths) {
            truncated[0] = true;
            return;
        }

        List<GraphEdge> candidates = adjacency.getOrDefault(current.id(), List.of());
        boolean expanded = false;
        if (edges.size() < maxDepth) {
            for (GraphEdge edge : candidates) {
                GraphNode next = reverse ? edge.caller() : edge.callee();
                if (!visited.add(next.id())) {
                    continue;
                }
                expanded = true;
                nodes.add(next);
                edges.add(edge);
                dfs(next, adjacency, reverse, maxDepth, maxPaths, visited, nodes, edges, paths, truncated);
                edges.remove(edges.size() - 1);
                nodes.remove(nodes.size() - 1);
                visited.remove(next.id());
                if (paths.size() >= maxPaths) {
                    truncated[0] = true;
                    break;
                }
            }
        } else if (!candidates.isEmpty()) {
            truncated[0] = true;
        }

        if (!expanded && paths.size() < maxPaths) {
            paths.add(new PathRecord(new ArrayList<>(nodes), new ArrayList<>(edges)));
        }
    }

    private EnumerationResult combine(List<PathRecord> upstreamPaths, List<PathRecord> downstreamPaths, int maxPaths) {
        Map<String, PathRecord> combined = new LinkedHashMap<>();
        boolean truncated = false;
        for (PathRecord upstream : upstreamPaths) {
            for (PathRecord downstream : downstreamPaths) {
                List<GraphNode> nodes = new ArrayList<>(upstream.nodes());
                nodes.addAll(downstream.nodes().subList(1, downstream.nodes().size()));
                List<GraphEdge> edges = new ArrayList<>(upstream.edges());
                edges.addAll(downstream.edges());
                PathRecord path = new PathRecord(nodes, edges);
                combined.putIfAbsent(pathKey(path), path);
                if (combined.size() >= maxPaths) {
                    truncated = true;
                    return new EnumerationResult(new ArrayList<>(combined.values()), truncated);
                }
            }
        }
        return new EnumerationResult(new ArrayList<>(combined.values()), truncated);
    }

    private PathRecord reverse(PathRecord path) {
        List<GraphNode> nodes = new ArrayList<>(path.nodes());
        List<GraphEdge> edges = new ArrayList<>(path.edges());
        Collections.reverse(nodes);
        Collections.reverse(edges);
        return new PathRecord(nodes, edges);
    }

    private Map<String, PathRecord> indexPaths(List<PathRecord> paths) {
        Map<String, PathRecord> byKey = new LinkedHashMap<>();
        for (PathRecord path : paths) {
            byKey.putIfAbsent(pathKey(path), path);
        }
        return byKey;
    }

    private String pathKey(PathRecord path) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < path.nodes().size(); i++) {
            if (i > 0) {
                key.append(" -").append(path.edges().get(i - 1).callType()).append("-> ");
            }
            key.append(path.nodes().get(i).signature());
        }
        return key.toString();
    }

    private CodePathDTO toDTO(String direction, PathRecord path) {
        List<CodePathStepDTO> steps = new ArrayList<>();
        for (int i = 0; i < path.nodes().size(); i++) {
            GraphNode graphNode = path.nodes().get(i);
            GraphEdge incoming = i > 0 ? path.edges().get(i - 1) : null;
            steps.add(new CodePathStepDTO(
                    toMethodNodeDTO(graphNode.node()),
                    incoming != null ? incoming.callType() : "ROOT",
                    incoming != null ? incoming.lineNumber() : 0,
                    nullableStringProperty(graphNode.node(), "sourceFilePath"),
                    nullableStringProperty(graphNode.node(), "sourceCode")
            ));
        }
        return new CodePathDTO(direction, steps);
    }

    private GraphNode graphNode(Node node) {
        return new GraphNode(node.elementId(), node, stringProperty(node, "signature", ""));
    }

    private MethodNodeDTO toMethodNodeDTO(Node node) {
        List<String> parameterTypes = node.containsKey("parameterTypes") && !node.get("parameterTypes").isNull()
                ? node.get("parameterTypes").asList(Value::asString)
                : List.of();
        return new MethodNodeDTO(
                nullableStringProperty(node, "qualifiedClassName"),
                nullableStringProperty(node, "methodName"),
                parameterTypes,
                nullableStringProperty(node, "returnType"),
                nullableStringProperty(node, "modifiers"),
                nullableStringProperty(node, "signature")
        );
    }

    private Map<String, Object> scopeParameters(String branch, String commitId) {
        Map<String, Object> params = new HashMap<>();
        params.put("branch", branch != null ? branch : "");
        params.put("commitId", commitId != null ? commitId : "");
        return params;
    }

    private String nullableStringProperty(Node node, String property) {
        String value = stringProperty(node, property, "");
        return value.isBlank() ? null : value;
    }

    private String stringProperty(org.neo4j.driver.types.Entity entity, String property, String defaultValue) {
        return entity.containsKey(property) && !entity.get(property).isNull()
                ? entity.get(property).asString()
                : defaultValue;
    }

    private int intProperty(org.neo4j.driver.types.Entity entity, String property, int defaultValue) {
        return entity.containsKey(property) && !entity.get(property).isNull()
                ? entity.get(property).asInt()
                : defaultValue;
    }

    private int normalizeMaxDepth(Integer maxDepth) {
        int normalized = maxDepth != null ? maxDepth : DEFAULT_MAX_DEPTH;
        if (normalized < 1 || normalized > MAX_DEPTH) {
            throw new ValidationException("maxDepth must be between 1 and " + MAX_DEPTH);
        }
        return normalized;
    }

    private int normalizeMaxPaths(Integer maxPaths) {
        int normalized = maxPaths != null ? maxPaths : DEFAULT_MAX_PATHS;
        if (normalized < 1 || normalized > MAX_PATHS) {
            throw new ValidationException("maxPaths must be between 1 and " + MAX_PATHS);
        }
        return normalized;
    }

    private record GraphNode(String id, Node node, String signature) {}

    private record GraphEdge(GraphNode caller, GraphNode callee, String callType, int lineNumber) {}

    private record GraphSnapshot(
            Map<String, GraphNode> nodesById,
            Map<String, List<GraphEdge>> outgoing,
            Map<String, List<GraphEdge>> incoming
    ) {}

    private record PathRecord(List<GraphNode> nodes, List<GraphEdge> edges) {}

    private record EnumerationResult(List<PathRecord> paths, boolean truncated) {}
}
