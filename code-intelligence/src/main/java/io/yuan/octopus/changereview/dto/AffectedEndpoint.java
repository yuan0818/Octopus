package io.yuan.octopus.changereview.dto;

import java.util.List;

/**
 * An HTTP API endpoint affected (directly or indirectly) by a change.
 *
 * @param projectId              owning {@code AnalyzedProjectEntity} id (Neo4j scope)
 * @param qualifiedClassName     endpoint controller FQN
 * @param methodName             endpoint method name
 * @param signature              endpoint method signature (Neo4n MERGE key)
 * @param httpMethod             HTTP verb (GET/POST/...)
 * @param httpPath               mapped path
 * @param changedMethodsReached  changed-method signatures this endpoint can reach via its call chain
 * @param impactPaths            representative impact paths (each a list of signatures endpoint → ... → changed)
 */
public record AffectedEndpoint(Long projectId,
                               String qualifiedClassName,
                               String methodName,
                               String signature,
                               String httpMethod,
                               String httpPath,
                               List<String> changedMethodsReached,
                               List<List<String>> impactPaths) {
}
