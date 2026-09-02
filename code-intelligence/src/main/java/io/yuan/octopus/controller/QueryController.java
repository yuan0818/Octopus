package io.yuan.octopus.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.dto.request.SourcePathRequest;
import io.yuan.octopus.dto.response.*;
import io.yuan.octopus.service.PathQueryService;
import io.yuan.octopus.service.QueryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService queryService;
    private final PathQueryService pathQueryService;

    public QueryController(QueryService queryService, PathQueryService pathQueryService) {
        this.queryService = queryService;
        this.pathQueryService = pathQueryService;
    }

    @GetMapping("/callers")
    public Result<List<MethodEdgeDTO>> getCallers(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId) {
        return Result.success(queryService.getCallers(className, methodName, List.of(), branch, commitId));
    }

    @GetMapping("/callees")
    public Result<List<MethodEdgeDTO>> getCallees(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId) {
        return Result.success(queryService.getCallees(className, methodName, List.of(), branch, commitId));
    }

    @GetMapping("/call-chain")
    public Result<CallChainDTO> getCallChain(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam(defaultValue = "0") int maxDepth,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId) {
        return Result.success(queryService.getCallChain(className, methodName, List.of(), maxDepth, branch, commitId));
    }

    /**
     * Returns both upstream callers and downstream callees for a method call trace.
     * Designed for graph visualization — returns flat edge lists with configurable depth.
     */
    @GetMapping("/trace-method")
    public Result<CallTraceDTO> traceMethod(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam(defaultValue = "5") int depth,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId) {
        return Result.success(queryService.traceMethod(className, methodName, List.of(), depth, branch, commitId));
    }

    /**
     * Enumerates upstream callers, downstream callees, and combined affected paths for a method.
     */
    @GetMapping("/impact-paths")
    public Result<ImpactPathsDTO> getImpactPaths(
            @RequestParam String signature,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId,
            @RequestParam(required = false) Integer maxDepth,
            @RequestParam(required = false) Integer maxPaths) {
        return Result.success(pathQueryService.getImpactPaths(
                signature, projectId, branch, commitId, maxDepth, maxPaths));
    }

    /**
     * Compares all downstream paths for an entry point between two stored graph snapshots.
     */
    @GetMapping("/endpoint-diff-paths")
    public Result<EndpointDiffPathsDTO> getEndpointDiffPaths(
            @RequestParam String signature,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String branch,
            @RequestParam String fromCommitId,
            @RequestParam String toCommitId,
            @RequestParam(required = false) Integer maxDepth,
            @RequestParam(required = false) Integer maxPaths) {
        return Result.success(pathQueryService.getEndpointDiffPaths(
                signature, projectId, branch, fromCommitId, toCommitId, maxDepth, maxPaths));
    }

    /**
     * Alias of {@code /endpoint-diff-paths} for non-HTTP entry points.
     */
    @GetMapping("/entry-point-diff-paths")
    public Result<EndpointDiffPathsDTO> getEntryPointDiffPaths(
            @RequestParam String signature,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String branch,
            @RequestParam String fromCommitId,
            @RequestParam String toCommitId,
            @RequestParam(required = false) Integer maxDepth,
            @RequestParam(required = false) Integer maxPaths) {
        return Result.success(pathQueryService.getEndpointDiffPaths(
                signature, projectId, branch, fromCommitId, toCommitId, maxDepth, maxPaths));
    }

    /**
     * Restores source code for each method in an ordered stored path.
     */
    @PostMapping("/paths/source")
    public Result<SourcePathDTO> getSourcePath(@RequestBody SourcePathRequest request) {
        return Result.success(pathQueryService.getSourcePath(request));
    }

    @GetMapping("/classes/{qualifiedName}")
    public Result<ClassDetailDTO> getClassDetail(
            @PathVariable String qualifiedName,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId) {
        return Result.success(queryService.getClassDetail(qualifiedName, commitId));
    }

    @GetMapping("/packages/{packageName}/classes")
    public Result<List<ClassSummaryDTO>> listClassesByPackage(@PathVariable String packageName) {
        return Result.success(queryService.listClassesByPackage(packageName));
    }

    @GetMapping("/annotations/{annotationName}")
    public Result<List<AnnotatedElementDTO>> findByAnnotation(@PathVariable String annotationName) {
        return Result.success(queryService.findByAnnotation(annotationName));
    }

    @GetMapping("/implementors")
    public Result<List<ClassSummaryDTO>> getImplementors(@RequestParam String interfaceName) {
        return Result.success(queryService.getImplementors(interfaceName));
    }

    /**
     * Lists API endpoints found in the analysed code.
     *
     * @param projectId optional filter
     * @param branch optional branch filter（缺省 = HEAD）
     * @param commitId optional commit filter（缺省 = HEAD）
     */
    @GetMapping("/endpoints")
    public Result<List<EndpointDTO>> getEndpoints(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId) {
        return Result.success(queryService.getEndpoints(projectId, branch, commitId));
    }

    /**
     * Lists entry points found in the analysed code, with HTTP API entries ordered first by default.
     *
     * @param projectId optional filter
     * @param type optional entry-point type filter
     * @param branch optional branch filter（缺省 = HEAD）
     * @param commitId optional commit filter（缺省 = HEAD）
     */
    @GetMapping("/entry-points")
    public Result<List<EntryPointDTO>> getEntryPoints(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId) {
        return Result.success(queryService.getEntryPoints(projectId, type, branch, commitId));
    }

    /**
     * Lists all analysed projects with their git context (branch, commitId) and backend flag.
     */
    @GetMapping("/projects")
    public Result<List<ProjectSummaryDTO>> getProjects() {
        return Result.success(queryService.getProjects());
    }

    /**
     * 列出某项目关联 Git 仓库的所有分支（真实 Git 分支，来自 JGit）。
     */
    @GetMapping("/projects/{projectId}/branches")
    public Result<List<BranchInfo>> listBranches(@PathVariable Long projectId) {
        return Result.success(queryService.listBranches(projectId));
    }

    /**
     * 列出某项目某分支最近的 commit 历史（真实 Git commit，来自 JGit）。
     */
    @GetMapping("/projects/{projectId}/commits")
    public Result<List<CommitInfo>> listCommits(
            @PathVariable Long projectId,
            @RequestParam String branch,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(queryService.listCommits(projectId, branch, limit));
    }

    /**
     * 从元数据枚举项目已分析过的分支（本地项目的版本选择事实来源）。
     */
    @GetMapping("/projects/{projectId}/analyzed-branches")
    public Result<List<BranchInfo>> getAnalyzedBranches(@PathVariable Long projectId) {
        return Result.success(queryService.getAnalyzedBranches(projectId));
    }

    /** 从元数据枚举指定分支已分析过的 commit。 */
    @GetMapping("/projects/{projectId}/analyzed-commits")
    public Result<List<CommitInfo>> getAnalyzedCommits(
            @PathVariable Long projectId,
            @RequestParam String branch) {
        return Result.success(queryService.getAnalyzedCommits(projectId, branch));
    }

    /**
     * 同一入口在两个分析版本之间的链路成员 + 源码 diff（版本 = branch + commitId 对，
     * commitId 为空表示该分支 HEAD 快照）。
     */
    @GetMapping("/entry-chain-diff")
    public Result<EntryChainDiffDTO> getEntryChainDiff(
            @RequestParam Long projectId,
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam String fromBranch,
            @RequestParam(required = false) String fromCommitId,
            @RequestParam String toBranch,
            @RequestParam(required = false) String toCommitId,
            @RequestParam(required = false) Integer maxDepth) {
        return Result.success(pathQueryService.getEntryChainDiff(
                projectId, className, methodName,
                fromBranch, fromCommitId, toBranch, toCommitId, maxDepth));
    }

    /**
     * Returns a comprehensive correctness report for the analysis results.
     *
     * <p>The report includes:
     * <ul>
     *   <li>Graph statistics (Neo4j) – node/edge counts, CallType breakdown, isolated/root/leaf nodes</li>
     *   <li>Metadata statistics (MySQL) – class/method/field counts per project</li>
     *   <li>Cross-validation – consistency checks between Neo4j and MySQL</li>
     *   <li>Detected issues – automatically flagged problems with severity levels</li>
     *   <li>Verdict – overall assessment (OK / PARTIAL / INCOMPLETE)</li>
     * </ul>
     *
     * @param project optional project name substring filter (e.g. {@code betaota})
     */
    @GetMapping("/analysis-report")
    public Result<AnalysisReportDTO> getAnalysisReport(
            @RequestParam(required = false) String project) {
        return Result.success(queryService.getAnalysisReport(project));
    }
}
