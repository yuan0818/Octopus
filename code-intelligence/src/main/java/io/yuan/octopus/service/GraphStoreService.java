package io.yuan.octopus.service;

import io.yuan.octopus.domain.CallEdge;
import io.yuan.octopus.domain.CallGraph;
import io.yuan.octopus.domain.MethodNode;
import io.yuan.octopus.exception.GraphStoreException;
import jakarta.annotation.PostConstruct;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GraphStoreService {

    private static final Logger log = LoggerFactory.getLogger(GraphStoreService.class);

    /** Number of nodes/edges to batch in a single UNWIND Cypher call. */
    private static final int BATCH_SIZE = 2000;

    private final Driver driver;

    public GraphStoreService(Driver driver) {
        this.driver = driver;
    }

    public void storeGraph(Long projectId, String branch, CallGraph callGraph) {
        clearProjectGraph(projectId, branch);
        storeNodes(projectId, branch, callGraph.nodes());
        storeEdges(projectId, branch, List.of(projectId), callGraph.edges());
    }

    /**
     * Stores a graph snapshot without deleting snapshots from older commits.
     */
    public void storeGraph(Long projectId, String branch, String commitId, CallGraph callGraph) {
        clearProjectGraph(projectId, branch, commitId);
        storeNodes(projectId, branch, commitId, callGraph.nodes());
        storeEdges(projectId, branch, commitId, List.of(projectId), callGraph.edges());
    }

    /**
     * Persists only the {@link MethodNode} objects to Neo4j (no edges).
     * Each node is written as a MERGE on {@code (projectId, branch, signature)}.
     */
    public void storeNodes(Long projectId, String branch, Set<MethodNode> nodes) {
        storeNodes(projectId, branch, "legacy", nodes);
    }

    public void storeNodes(Long projectId, String branch, String commitId, Set<MethodNode> nodes) {
        if (nodes.isEmpty()) return;
        String graphVersion = normalizeCommitId(commitId);
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                List<Map<String, Object>> batch = nodes.stream()
                        .map(node -> nodeParameters(projectId, branch, graphVersion, node))
                        .collect(Collectors.toList());
                for (int i = 0; i < batch.size(); i += BATCH_SIZE) {
                    List<Map<String, Object>> chunk = batch.subList(i, Math.min(i + BATCH_SIZE, batch.size()));
                    tx.run("""
                        UNWIND $batch AS node
                        MERGE (m:MethodNode {
                            projectId: node.projectId,
                            branch: node.branch,
                            commitId: node.commitId,
                            signature: node.signature
                        })
                        SET m.qualifiedClassName = node.qualifiedClassName,
                            m.methodName = node.methodName,
                            m.parameterTypes = node.parameterTypes,
                            m.returnType = node.returnType,
                            m.modifiers = node.modifiers,
                            m.sourceFilePath = node.sourceFilePath,
                            m.sourceCode = node.sourceCode,
                            m.current = true
                        """, Map.of("batch", chunk));
                }
                return null;
            });
        } catch (Exception e) {
            throw new GraphStoreException("Failed to store nodes for project " + projectId + " branch " + branch, e);
        }
    }

    /**
     * Persists {@link CallEdge} relationships to Neo4j.
     * <p>
     * The callee {@code MATCH} is scoped to {@code relatedProjectIds} so that cross-module
     * edges can be created without risking false matches against nodes from unrelated repos
     * that happen to share the same branch name and method signature.
     * <p>
     * Call this only after all callee nodes have already been stored (see {@link #storeNodes}).
     *
     * @param projectId        the projectId of the calling module (used for the caller MATCH)
     * @param branch           the branch for both caller and callee MATCH
     * @param relatedProjectIds project IDs of all modules in the same workspace / analysis run;
     *                         limits callee lookup to this set, preventing cross-repo collisions
     * @param edges            the call edges to persist
     */
    public void storeEdges(Long projectId, String branch, List<Long> relatedProjectIds, Set<CallEdge> edges) {
        storeEdges(projectId, branch, "legacy", relatedProjectIds, edges);
    }

    public void storeEdges(Long projectId, String branch, String commitId,
                           List<Long> relatedProjectIds, Set<CallEdge> edges) {
        if (edges.isEmpty()) return;
        String graphVersion = normalizeCommitId(commitId);
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                List<Map<String, Object>> batch = edges.stream()
                        .map(edge -> {
                            Map<String, Object> params = new HashMap<>();
                            params.put("projectId", projectId);
                            params.put("branch", branch);
                            params.put("commitId", graphVersion);
                            params.put("callerSignature", edge.caller().signature());
                            params.put("calleeSignature", edge.callee().signature());
                            params.put("callType", edge.callType().name());
                            params.put("lineNumber", edge.lineNumber());
                            return params;
                        })
                        .collect(Collectors.toList());
                for (int i = 0; i < batch.size(); i += BATCH_SIZE) {
                    List<Map<String, Object>> chunk = batch.subList(i, Math.min(i + BATCH_SIZE, batch.size()));
                    tx.run("""
                        UNWIND $batch AS edge
                        MATCH (caller:MethodNode {
                            projectId: edge.projectId,
                            branch: edge.branch,
                            commitId: edge.commitId,
                            signature: edge.callerSignature
                        })
                        MATCH (callee:MethodNode {
                            branch: edge.branch,
                            commitId: edge.commitId,
                            signature: edge.calleeSignature
                        })
                          WHERE callee.projectId IN $relatedProjectIds
                        MERGE (caller)-[r:CALLS {
                            callType: edge.callType,
                            lineNumber: edge.lineNumber,
                            projectId: edge.projectId,
                            branch: edge.branch,
                            commitId: edge.commitId
                        }]->(callee)
                        SET r.current = true
                        """, Map.of("batch", chunk, "relatedProjectIds", relatedProjectIds));
                }
                return null;
            });
        } catch (Exception e) {
            throw new GraphStoreException("Failed to store edges for project " + projectId + " branch " + branch, e);
        }
    }

    public void clearProjectGraph(Long projectId, String branch) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (m:MethodNode {projectId: $projectId, branch: $branch}) DETACH DELETE m",
                    Map.of("projectId", projectId, "branch", branch));
                return null;
            });
        } catch (Exception e) {
            throw new GraphStoreException("Failed to clear graph for project " + projectId + " branch " + branch, e);
        }
    }

    /**
     * Clears one graph version and marks older snapshots as historical.
     */
    public void clearProjectGraph(Long projectId, String branch, String commitId) {
        String graphVersion = normalizeCommitId(commitId);
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (m:MethodNode {projectId: $projectId, branch: $branch})
                    SET m.current = false
                    """, Map.of("projectId", projectId, "branch", branch));
                tx.run("""
                    MATCH (m:MethodNode {projectId: $projectId, branch: $branch, commitId: $commitId})
                    DETACH DELETE m
                    """, Map.of("projectId", projectId, "branch", branch, "commitId", graphVersion));
                return null;
            });
        } catch (Exception e) {
            throw new GraphStoreException("Failed to clear graph snapshot for project " + projectId
                    + " branch " + branch + " commit " + graphVersion, e);
        }
    }

    /**
     * Reuses the current graph for a new commit when a Git diff contains no Java changes.
     * Nodes and relationships are copied so every analysed commit remains independently
     * queryable without paying the cost of another PSI run.
     */
    public void copyCurrentSnapshots(List<Long> projectIds, String branch, String commitId) {
        if (projectIds.isEmpty()) return;
        String graphVersion = normalizeCommitId(commitId);
        Map<String, Object> params = Map.of(
                "projectIds", projectIds,
                "branch", branch,
                "commitId", graphVersion
        );

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                long targetCount = tx.run("""
                    MATCH (target:MethodNode)
                    WHERE target.projectId IN $projectIds
                      AND target.branch = $branch
                      AND target.commitId = $commitId
                    RETURN count(target) AS count
                    """, params).single().get("count").asLong();

                if (targetCount == 0) {
                    long sourceCount = tx.run("""
                        MATCH (source:MethodNode)
                        WHERE source.projectId IN $projectIds
                          AND source.branch = $branch
                          AND coalesce(source.current, true) = true
                        RETURN count(source) AS count
                        """, params).single().get("count").asLong();
                    if (sourceCount == 0) {
                        log.warn("No current graph snapshot to copy for projects {} branch {}", projectIds, branch);
                        return null;
                    }

                    tx.run("""
                        MATCH (source:MethodNode)
                        WHERE source.projectId IN $projectIds
                          AND source.branch = $branch
                          AND coalesce(source.current, true) = true
                        MERGE (copy:MethodNode {
                            projectId: source.projectId,
                            branch: $branch,
                            commitId: $commitId,
                            signature: source.signature
                        })
                        SET copy = properties(source),
                            copy.commitId = $commitId,
                            copy.current = true
                        """, params);
                    tx.run("""
                        MATCH (caller:MethodNode)-[source:CALLS]->(callee:MethodNode)
                        WHERE caller.projectId IN $projectIds
                          AND callee.projectId IN $projectIds
                          AND caller.branch = $branch
                          AND callee.branch = $branch
                          AND coalesce(caller.current, true) = true
                          AND coalesce(callee.current, true) = true
                          AND coalesce(caller.commitId, '') <> $commitId
                          AND coalesce(callee.commitId, '') <> $commitId
                        MATCH (callerCopy:MethodNode {
                            projectId: caller.projectId,
                            branch: $branch,
                            commitId: $commitId,
                            signature: caller.signature
                        })
                        MATCH (calleeCopy:MethodNode {
                            projectId: callee.projectId,
                            branch: $branch,
                            commitId: $commitId,
                            signature: callee.signature
                        })
                        MERGE (callerCopy)-[copy:CALLS {
                            callType: coalesce(source.callType, 'DIRECT'),
                            lineNumber: coalesce(source.lineNumber, 0),
                            projectId: caller.projectId,
                            branch: $branch,
                            commitId: $commitId
                        }]->(calleeCopy)
                        SET copy.current = true
                        """, params);
                }

                tx.run("""
                    MATCH (m:MethodNode)
                    WHERE m.projectId IN $projectIds AND m.branch = $branch
                    SET m.current = false
                    """, params);
                tx.run("""
                    MATCH (m:MethodNode)
                    WHERE m.projectId IN $projectIds
                      AND m.branch = $branch
                      AND m.commitId = $commitId
                    SET m.current = true
                    """, params);
                tx.run("""
                    MATCH (caller:MethodNode)-[r:CALLS]->()
                    WHERE caller.projectId IN $projectIds AND caller.branch = $branch
                    SET r.current = false
                    """, params);
                tx.run("""
                    MATCH (caller:MethodNode)-[r:CALLS]->()
                    WHERE caller.projectId IN $projectIds
                      AND caller.branch = $branch
                      AND caller.commitId = $commitId
                    SET r.current = true
                    """, params);
                return null;
            });
        } catch (Exception e) {
            throw new GraphStoreException("Failed to copy current graph snapshot for projects " + projectIds
                    + " branch " + branch + " commit " + graphVersion, e);
        }
    }

    private Map<String, Object> nodeParameters(Long projectId, String branch, String commitId, MethodNode node) {
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("branch", branch);
        params.put("commitId", commitId);
        params.put("signature", node.signature());
        params.put("qualifiedClassName", node.qualifiedClassName());
        params.put("methodName", node.methodName());
        params.put("parameterTypes", node.parameterTypes().toArray(new String[0]));
        params.put("returnType", node.returnType());
        params.put("modifiers", node.modifiers());
        params.put("sourceFilePath", node.sourceFilePath() != null ? node.sourceFilePath() : "");
        params.put("sourceCode", node.sourceCode() != null ? node.sourceCode() : "");
        return params;
    }

    private String normalizeCommitId(String commitId) {
        return commitId == null || commitId.isBlank() ? "local" : commitId;
    }

    @PostConstruct
    public void ensureIndexes() {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("DROP CONSTRAINT method_node_unique IF EXISTS");
                tx.run("CREATE CONSTRAINT method_node_version_unique IF NOT EXISTS FOR (m:MethodNode) REQUIRE (m.projectId, m.branch, m.commitId, m.signature) IS UNIQUE");
                tx.run("CREATE INDEX method_node_branch_commit_sig_idx IF NOT EXISTS FOR (m:MethodNode) ON (m.branch, m.commitId, m.signature)");
                tx.run("CREATE INDEX method_node_class_idx IF NOT EXISTS FOR (m:MethodNode) ON (m.qualifiedClassName)");
                tx.run("CREATE INDEX method_node_method_idx IF NOT EXISTS FOR (m:MethodNode) ON (m.methodName)");
                tx.run("CREATE INDEX method_node_project_idx IF NOT EXISTS FOR (m:MethodNode) ON (m.projectId)");
                tx.run("CREATE INDEX method_node_branch_idx IF NOT EXISTS FOR (m:MethodNode) ON (m.branch)");
                tx.run("CREATE INDEX method_node_current_idx IF NOT EXISTS FOR (m:MethodNode) ON (m.current)");
                return null;
            });
        } catch (Exception e) {
            log.warn("Failed to create Neo4j indexes/constraints (Neo4j might not be available): {}", e.getMessage());
        }
    }
}
