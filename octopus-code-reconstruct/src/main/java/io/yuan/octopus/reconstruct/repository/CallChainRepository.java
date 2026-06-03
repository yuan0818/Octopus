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
 */
@Repository
public class CallChainRepository {

    private final Driver neo4jDriver;

    public CallChainRepository(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /** Fetch the full signature for a known class + method. */
    public String findSignature(String className, String methodName) {
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(
                "MATCH (m:MethodNode) " +
                "WHERE m.qualifiedClassName = $className " +
                "  AND m.methodName = $methodName " +
                "  AND coalesce(m.current, true) = true " +
                "RETURN m.signature AS sig LIMIT 1",
                Map.of("className", className, "methodName", methodName));
            if (result.hasNext()) {
                return result.next().get("sig").asString();
            }
        }
        return null;
    }

    /** Return all direct callees for a given caller, excluding SPRING_DI edges. */
    public List<CalleeRow> findDirectCallees(String className, String methodName) {
        String cypher =
            "MATCH (caller:MethodNode)-[c:CALLS]->(callee:MethodNode) " +
            "WHERE caller.qualifiedClassName = $className " +
            "  AND caller.methodName = $methodName " +
            "  AND coalesce(caller.current, true) = true " +
            "  AND coalesce(callee.current, true) = true " +
            "  AND c.callType <> 'SPRING_DI' " +
            "RETURN DISTINCT callee.qualifiedClassName AS cls, " +
            "                callee.methodName AS method, " +
            "                callee.signature AS sig";
        List<CalleeRow> results = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, Map.of("className", className, "methodName", methodName));
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
