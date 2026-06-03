package io.yuan.octopus.service;

import io.yuan.octopus.dto.request.SourcePathRequest;
import io.yuan.octopus.dto.response.CodePathDTO;
import io.yuan.octopus.dto.response.CodePathDTO.CodePathStepDTO;
import io.yuan.octopus.dto.response.EndpointDiffPathsDTO;
import io.yuan.octopus.dto.response.ImpactPathsDTO;
import io.yuan.octopus.dto.response.MethodNodeDTO;
import io.yuan.octopus.dto.response.SourcePathDTO;
import io.yuan.octopus.exception.ResourceNotFoundException;
import io.yuan.octopus.exception.ValidationException;
import io.yuan.octopus.repository.JavaProjectRepository;
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
    private final JavaProjectRepository javaProjectRepository;

    public PathQueryService(Driver neo4jDriver, JavaProjectRepository javaProjectRepository) {
        this.neo4jDriver = neo4jDriver;
        this.javaProjectRepository = javaProjectRepository;
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
