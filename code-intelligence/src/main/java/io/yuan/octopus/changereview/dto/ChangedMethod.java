package io.yuan.octopus.changereview.dto;

/**
 * A method whose signature/source changed between base and patchset commits.
 *
 * @param projectId        owning {@code AnalyzedProjectEntity} id (Neo4j CALLS graph scope)
 * @param qualifiedClassName fully-qualified class name
 * @param methodName       method name
 * @param signature        full method signature (Neo4j MethodNode MERGE key)
 * @param filePath         absolute source file path (merge version)
 * @param changeType       {@code ADDED} / {@code MODIFIED} / {@code DELETED}
 */
public record ChangedMethod(Long projectId,
                            String qualifiedClassName,
                            String methodName,
                            String signature,
                            String filePath,
                            String changeType) {
}
