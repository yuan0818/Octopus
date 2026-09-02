package io.yuan.octopus.changereview.service;

import io.yuan.octopus.changereview.dto.AffectedEndpoint;
import io.yuan.octopus.changereview.dto.ChangedMethod;
import io.yuan.octopus.dto.response.EndpointDTO;
import io.yuan.octopus.service.QueryService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Identifies all HTTP API endpoints affected by a set of changed methods via
 * reverse (upstream) traversal of the Neo4j CALLS graph (REQ-CTR-006).
 *
 * <p>For each changed method it queries the merge-version CALLS graph for all
 * upstream methods that can reach it (caller direction), then intersects those
 * with {@link QueryService#getEndpoints} to find affected HTTP endpoints.
 *
 * <p>Per BR-CTR-031 the impact set is not pruned by hop count — Neo4j's
 * variable-length path dedups nodes so traversal terminates on the finite
 * merge-version graph. A generous hop cap (30) is applied as a safety bound and
 * matches {@code PathQueryService}'s maximum; real call chains rarely exceed it.
 *
 * <p>Per BR-CTR-030 an endpoint reachable from multiple changed methods is
 * counted once but records every changed method it reaches.
 */
@Service
public class AffectedEndpointService {

    private static final Logger log = LoggerFactory.getLogger(AffectedEndpointService.class);

    /** Safety hop cap on reverse traversal (PathQueryService max; effectively unbounded for real chains). */
    private static final int MAX_REVERSE_HOPS = 30;

    private static final String REVERSE_REACHABLE_CYPHER = """
            MATCH path = (caller:MethodNode)-[:CALLS*1..%d]->(changed:MethodNode)
            WHERE changed.signature = $sig
              AND changed.projectId = $pid
              AND caller.projectId = $pid
              AND changed.commitId = $cid
              AND caller.commitId = $cid
              AND ALL(r IN relationships(path) WHERE r.commitId = $cid AND r.callType <> 'SPRING_DI')
            RETURN DISTINCT caller.signature AS sig
            """.formatted(MAX_REVERSE_HOPS);

    private final Driver neo4jDriver;
    private final QueryService queryService;

    public AffectedEndpointService(Driver neo4jDriver, QueryService queryService) {
        this.neo4jDriver = neo4jDriver;
        this.queryService = queryService;
    }

    /**
     * @param branch         target branch
     * @param mergeCommitId  merge commit SHA (version key of the analysed CALLS graph)
     * @param changedMethods ADDED/MODIFIED methods (DELETED filtered out by caller per BR-CTR-024)
     * @return affected endpoints (deduped by signature), each carrying the changed methods it reaches
     */
    public List<AffectedEndpoint> getAffectedEndpoints(String branch, String mergeCommitId,
                                                       List<ChangedMethod> changedMethods) {
        if (changedMethods.isEmpty()) {
            return List.of();
        }

        // Group changed methods by projectId (a repo may span multiple modules/projects)
        Map<Long, List<ChangedMethod>> byProject = new HashMap<>();
        for (ChangedMethod cm : changedMethods) {
            if (cm.projectId() == null) continue;
            if (!"ADDED".equals(cm.changeType()) && !"MODIFIED".equals(cm.changeType())) {
                continue; // DELETED not a reverse-traversal seed (BR-CTR-024)
            }
            byProject.computeIfAbsent(cm.projectId(), k -> new ArrayList<>()).add(cm);
        }

        List<AffectedEndpoint> affected = new ArrayList<>();
        for (Map.Entry<Long, List<ChangedMethod>> entry : byProject.entrySet()) {
            Long projectId = entry.getKey();
            List<ChangedMethod> projectChanged = entry.getValue();
            affected.addAll(findAffectedForProject(projectId, branch, mergeCommitId, projectChanged));
        }

        log.info("Identified {} affected endpoints across {} project(s) for merge {}",
                affected.size(), byProject.size(), mergeCommitId);
        return affected;
    }

    private List<AffectedEndpoint> findAffectedForProject(Long projectId, String branch, String mergeCommitId,
                                                           List<ChangedMethod> projectChanged) {
        // All HTTP endpoints of the merge version for this project.
        List<EndpointDTO> endpoints = queryService.getEndpoints(projectId, branch, mergeCommitId);
        Map<String, EndpointDTO> endpointBySig = new LinkedHashMap<>();
        for (EndpointDTO e : endpoints) {
            if (e.signature() != null) {
                endpointBySig.putIfAbsent(e.signature(), e);
            }
        }
        if (endpointBySig.isEmpty()) {
            return List.of();
        }

        // endpointSig -> set of changed-method signatures it reaches
        Map<String, Set<String>> endpointReachedBy = new HashMap<>();
        for (EndpointDTO e : endpoints) endpointReachedBy.put(e.signature(), new LinkedHashSet<>());

        for (ChangedMethod cm : projectChanged) {
            // (a) the changed method itself is an endpoint → directly affected
            if (endpointBySig.containsKey(cm.signature())) {
                endpointReachedBy.get(cm.signature()).add(cm.signature());
            }
            // (b) reverse-reachable upstream methods that are endpoints → indirectly affected
            Set<String> reachable = queryReverseReachableSignatures(projectId, mergeCommitId, cm.signature());
            for (String sig : reachable) {
                if (endpointReachedBy.containsKey(sig)) {
                    endpointReachedBy.get(sig).add(cm.signature());
                }
            }
        }

        List<AffectedEndpoint> result = new ArrayList<>();
        for (EndpointDTO e : endpoints) {
            Set<String> reached = endpointReachedBy.get(e.signature());
            if (reached == null || reached.isEmpty()) continue;
            List<List<String>> impactPaths = reached.stream()
                    .map(changedSig -> List.of(e.signature(), changedSig))
                    .toList();
            result.add(new AffectedEndpoint(
                    projectId,
                    e.qualifiedClassName(),
                    e.methodName(),
                    e.signature(),
                    e.httpMethod(),
                    e.path(),
                    new ArrayList<>(reached),
                    impactPaths
            ));
        }
        return result;
    }

    /**
     * Returns the set of upstream (caller) method signatures that can reach the
     * given changed method via the merge-version CALLS graph (excluding the
     * changed method itself).
     * <p>
     * Neo4j 查询失败时抛出异常（不静默返回空集）：静默降级会把"图不可用"伪装成
     * "无影响面"，任务带着漏报的审查结论 COMPLETED。由 orchestrator 标记
     * FAILED(IMPACT_FAILED) 走重试。
     */
    private Set<String> queryReverseReachableSignatures(Long projectId, String mergeCommitId, String changedSig) {
        Set<String> reachable = new HashSet<>();
        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("sig", changedSig);
            params.put("pid", projectId);
            params.put("cid", mergeCommitId);
            Result result = session.run(REVERSE_REACHABLE_CYPHER, params);
            while (result.hasNext()) {
                Record record = result.next();
                if (!record.get("sig").isNull()) {
                    reachable.add(record.get("sig").asString());
                }
            }
            return reachable;
        } catch (Exception e) {
            log.error("Reverse-reachable query failed for changed sig {} (projectId={} merge={})",
                    changedSig, projectId, mergeCommitId, e);
            throw new IllegalStateException(
                    "Impact query failed (Neo4j) for changed method " + changedSig + ": " + e.getMessage(), e);
        }
    }
}
