package io.yuan.octopus.reconstruct.repository;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Data-access layer for Neo4j call-graph queries.
 * All Cypher statements are centralized here, keeping domain
 * logic (BFS traversal, dedup) in the service layer.
 * <p>
 * 版本过滤（对应 BR-DIFF-011）：
 *   commitId 非空 → 精确匹配 m.commitId = $commitId
 *   commitId 为空 → 取 HEAD（coalesce(m.current, true) = true）
 *   branch 非空 → 追加 m.branch = $branch（多分支共 analysed 时限定分支）
 */
@Repository
public class CallChainRepository {

    private final Driver neo4jDriver;

    public CallChainRepository(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /**
     * 构造节点版本过滤 Cypher 片段。
     */
    private String versionFilter(String nodeAlias, boolean versioned) {
        return versioned
                ? nodeAlias + ".commitId = $commitId"
                : "coalesce(" + nodeAlias + ".current, true) = true";
    }

    /** branch 过滤片段：branch 为空时恒真（不限制）。 */
    private String branchFilter(String nodeAlias, String branch) {
        if (branch == null || branch.isBlank()) {
            return "true";
        }
        return nodeAlias + ".branch = $branch";
    }

    /** Fetch the full signature for a known class + method. */
    public String findSignature(Long projectId, String className, String methodName, String commitId) {
        return findSignature(projectId, className, methodName, commitId, null);
    }

    public String findSignature(Long projectId, String className, String methodName,
                                String commitId, String branch) {
        boolean versioned = commitId != null && !commitId.isBlank();
        boolean branched = branch != null && !branch.isBlank();
        String cypher =
            "MATCH (m:MethodNode) " +
            "WHERE m.projectId = $projectId " +
            "  AND m.qualifiedClassName = $className " +
            "  AND m.methodName = $methodName " +
            "  AND " + versionFilter("m", versioned) + " " +
            "  AND " + branchFilter("m", branch) + " " +
            "RETURN m.signature AS sig LIMIT 1";
        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("className", className);
            params.put("methodName", methodName);
            if (versioned) params.put("commitId", commitId);
            if (branched) params.put("branch", branch);
            Result result = session.run(cypher, params);
            if (result.hasNext()) {
                return result.next().get("sig").asString();
            }
        }
        return null;
    }

    /** Return all direct callees for a given caller, excluding SPRING_DI edges. */
    public List<CalleeRow> findDirectCallees(Long projectId, String className, String methodName, String commitId) {
        return findDirectCallees(projectId, className, methodName, commitId, null);
    }

    public List<CalleeRow> findDirectCallees(Long projectId, String className, String methodName,
                                             String commitId, String branch) {
        boolean versioned = commitId != null && !commitId.isBlank();
        boolean branched = branch != null && !branch.isBlank();
        String callerFilter = versionFilter("caller", versioned);
        String calleeFilter = versionFilter("callee", versioned);
        String cypher =
            "MATCH (caller:MethodNode)-[c:CALLS]->(callee:MethodNode) " +
            "WHERE caller.projectId = $projectId " +
            "  AND caller.qualifiedClassName = $className " +
            "  AND caller.methodName = $methodName " +
            "  AND " + callerFilter + " " +
            "  AND " + calleeFilter + " " +
            "  AND " + branchFilter("caller", branch) + " " +
            "  AND " + branchFilter("callee", branch) + " " +
            "  AND c.callType <> 'SPRING_DI' " +
            "RETURN DISTINCT callee.qualifiedClassName AS cls, " +
            "                callee.methodName AS method, " +
            "                callee.signature AS sig";
        List<CalleeRow> results = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("className", className);
            params.put("methodName", methodName);
            if (versioned) params.put("commitId", commitId);
            if (branched) params.put("branch", branch);
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                var record = result.next();
                results.add(new CalleeRow(
                    record.get("cls").asString(),
                    record.get("method").asString(),
                    record.get("sig").asString()));
            }
        }
        return results;
    }

    /** Return all direct callers for a given callee (reverse direction). */
    public List<CalleeRow> findDirectCallers(Long projectId, String className, String methodName, String commitId) {
        return findDirectCallers(projectId, className, methodName, commitId, null);
    }

    public List<CalleeRow> findDirectCallers(Long projectId, String className, String methodName,
                                             String commitId, String branch) {
        boolean versioned = commitId != null && !commitId.isBlank();
        boolean branched = branch != null && !branch.isBlank();
        String callerFilter = versionFilter("caller", versioned);
        String calleeFilter = versionFilter("callee", versioned);
        String cypher =
            "MATCH (caller:MethodNode)-[c:CALLS]->(callee:MethodNode) " +
            "WHERE callee.projectId = $projectId " +
            "  AND callee.qualifiedClassName = $className " +
            "  AND callee.methodName = $methodName " +
            "  AND " + callerFilter + " " +
            "  AND " + calleeFilter + " " +
            "  AND " + branchFilter("caller", branch) + " " +
            "  AND " + branchFilter("callee", branch) + " " +
            "RETURN DISTINCT caller.qualifiedClassName AS cls, " +
            "                caller.methodName AS method, " +
            "                caller.signature AS sig";
        List<CalleeRow> results = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("className", className);
            params.put("methodName", methodName);
            if (versioned) params.put("commitId", commitId);
            if (branched) params.put("branch", branch);
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                var record = result.next();
                results.add(new CalleeRow(
                    record.get("cls").asString(),
                    record.get("method").asString(),
                    record.get("sig").asString()));
            }
        }
        return results;
    }

    /** Extract the qualified class name from a signature like {@code com.x.X.method():R}. */
    public static String extractClassName(String signature) {
        int dotIdx = signature.lastIndexOf('.');
        if (dotIdx <= 0) return signature;
        String beforeMethod = signature.substring(0, dotIdx);
        int parenIdx = beforeMethod.lastIndexOf('(');
        return parenIdx > 0 ? beforeMethod.substring(0, parenIdx) : beforeMethod;
    }

    public record CalleeRow(String className, String methodName, String signature) {}
}
