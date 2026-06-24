package io.yuan.octopus.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Analysis correctness report returned by {@code GET /api/query/analysis-report}.
 *
 * <p>The report is divided into several sections:
 * <ul>
 *   <li>{@link GraphStats} – raw counts from Neo4j</li>
 *   <li>{@link MetadataStats} – raw counts from MySQL, per project</li>
 *   <li>{@link CrossValidation} – consistency checks between Neo4j and MySQL</li>
 *   <li>{@link IssueList} – automatically detected problems</li>
 * </ul>
 */
public record AnalysisReportDTO(
        GraphStats graphStats,
        List<MetadataStats> metadataStats,
        CrossValidation crossValidation,
        IssueList issues,
        String verdict
) {

    /**
     * Raw statistics pulled from Neo4j (graph layer).
     *
     * @param totalNodes            total number of MethodNode nodes in the graph
     * @param totalEdges            total number of CALLS relationships
     * @param edgesByCallType       breakdown of edge count per CallType
     * @param isolatedNodeCount     nodes with neither incoming nor outgoing edges
     * @param noOutgoingEdgeCount   nodes with no outgoing edges (leaf methods)
     * @param noIncomingEdgeCount   nodes with no incoming edges (root entry points)
     * @param virtualNodeCount      placeholder/virtual nodes created for DI/scheduler targets
     */
    public record GraphStats(
            long totalNodes,
            long totalEdges,
            Map<String, Long> edgesByCallType,
            long isolatedNodeCount,
            long noOutgoingEdgeCount,
            long noIncomingEdgeCount,
            long virtualNodeCount
    ) {}

    /**
     * Raw statistics from MySQL (metadata layer) for one project.
     *
     * @param projectId   internal database project id
     * @param projectName project name
     * @param branch      branch name
     * @param analyzedTag last successfully analysed Git tag
     * @param classCount  number of classes parsed into MySQL
     * @param methodCount number of methods parsed into MySQL
     * @param fieldCount  number of fields parsed into MySQL
     */
    public record MetadataStats(
            Long projectId,
            String projectName,
            String branch,
            String analyzedTag,
            long classCount,
            long methodCount,
            long fieldCount
    ) {}

    /**
     * Cross-validation results comparing Neo4j graph data with MySQL metadata.
     *
     * @param neo4jMethodNodeCount  total MethodNode count in Neo4j
     * @param mysqlMethodCount      total MethodInfo count in MySQL
     * @param mysqlClassCount       total ClassInfo count in MySQL
     * @param neo4jClassNodeCount   distinct qualifiedClassNames in Neo4j
     * @param classCountMatch       whether Neo4j class count ≈ MySQL class count (within 20% tolerance)
     * @param methodCountMatch      whether Neo4j node count ≈ MySQL method count (within 20% tolerance)
     * @param classesOnlyInMysql    sample of class names present in MySQL but absent from Neo4j
     * @param classesOnlyInNeo4j    sample of class names present in Neo4j but absent from MySQL
     */
    public record CrossValidation(
            long neo4jMethodNodeCount,
            long mysqlMethodCount,
            long mysqlClassCount,
            long neo4jClassNodeCount,
            boolean classCountMatch,
            boolean methodCountMatch,
            List<String> classesOnlyInMysql,
            List<String> classesOnlyInNeo4j
    ) {}

    /**
     * Collection of automatically detected issues that may indicate incomplete or incorrect analysis.
     *
     * @param totalIssues         total number of issues found
     * @param items               individual issue descriptions
     */
    public record IssueList(
            int totalIssues,
            List<String> items
    ) {}
}

