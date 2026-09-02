package io.yuan.octopus.reconstruct.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.reconstruct.dto.ReconstructRequest;
import io.yuan.octopus.reconstruct.dto.ReconstructResponse;
import io.yuan.octopus.reconstruct.repository.CallChainRepository;
import io.yuan.octopus.reconstruct.service.ApiEndpointDetector;
import io.yuan.octopus.reconstruct.service.CallChainCollector;
import io.yuan.octopus.reconstruct.service.CallChainCollector.CallChainResult;
import io.yuan.octopus.reconstruct.service.ReconstructService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ReconstructController {

    private final ReconstructService reconstructService;
    private final CallChainCollector chainCollector;
    private final ApiEndpointDetector apiEndpointDetector;

    public ReconstructController(ReconstructService reconstructService,
                                 CallChainCollector chainCollector,
                                 ApiEndpointDetector apiEndpointDetector) {
        this.reconstructService = reconstructService;
        this.chainCollector = chainCollector;
        this.apiEndpointDetector = apiEndpointDetector;
    }

    /**
     * 源码重建。
     * <p>
     * 新增可选参数 {@code branch}、{@code commitId}，缺省 = HEAD。
     * 指定 {@code commitId} 时优先通过 JGit 从 git 读取源码，失败时回退 MySQL 缓存。
     */
    @PostMapping("/reconstruct")
    public Result<ReconstructResponse> reconstruct(@Valid @RequestBody ReconstructRequest request) {
        try {
            ReconstructResponse response = reconstructService.reconstruct(request);
            return Result.success(response);
        } catch (NoSuchElementException e) {
            return Result.error(404, e.getMessage());
        }
    }

    /**
     * 调用链元数据——只返回调用链结构树（类名 + 方法签名），不包含源码。
     * <p>
     * 新增可选参数 {@code branch}、{@code commitId}，透传到 DAO 层用于版本过滤。
     */
    @GetMapping("/chain-metadata")
    public Result<ChainMetadataResponse> chainMetadata(
            @RequestParam @NotNull Long projectId,
            @RequestParam @NotBlank String className,
            @RequestParam @NotBlank String methodName,
            @RequestParam(defaultValue = "BFS") String strategy,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId) {

        CallChainResult chain = chainCollector.collect(projectId, className, methodName, strategy, branch, commitId);

        Map<String, Object> tree = buildChainTree(chain);

        ChainMetadataResponse response = new ChainMetadataResponse(
                className + "." + methodName,
                chain.involvedSignatures().size(),
                chain.involvedClasses().size(),
                new ArrayList<>(chain.involvedClasses()),
                tree
        );
        return Result.success(response);
    }

    /**
     * 反向调用链——从任意方法出发，向上追溯调用者，找到 API 端点。
     * <p>
     * 新增可选参数 {@code branch}、{@code commitId}，透传到 DAO 层用于版本过滤。
     */
    @GetMapping("/reverse-chain")
    public Result<ReverseChainResponse> reverseChain(
            @RequestParam @NotNull Long projectId,
            @RequestParam @NotBlank String className,
            @RequestParam @NotBlank String methodName,
            @RequestParam(defaultValue = "BFS") String strategy,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId) {

        CallChainResult upstream = chainCollector.collectUpstream(projectId, className, methodName, strategy, branch, commitId);

        List<ApiEndpointDetector.ApiEndpoint> endpoints = List.of();
        if (!upstream.involvedClasses().isEmpty()) {
            endpoints = apiEndpointDetector.detect(projectId, upstream.involvedClasses());
        }

        List<EndpointPath> endpointPaths = endpoints.stream()
                .map(ep -> {
                    String pathSummary = computeShortestPath(upstream, className, methodName, ep);
                    return new EndpointPath(ep.httpMethod(), ep.path(), ep.className(), ep.methodName(), pathSummary, ep.callType());
                })
                .distinct()
                .toList();

        ReverseChainResponse response = new ReverseChainResponse(
                className + "." + methodName,
                endpointPaths,
                new ArrayList<>(upstream.involvedClasses()),
                buildChainTree(upstream)
        );
        return Result.success(response);
    }

    // ── Response DTOs (inline) ──

    public record ChainMetadataResponse(
            String anchor,
            int totalSignatures,
            int totalClasses,
            List<String> involvedClasses,
            Map<String, Object> chainTree
    ) {}

    public record ReverseChainResponse(
            String target,
            List<EndpointPath> endpoints,
            List<String> involvedClasses,
            Map<String, Object> chainTree
    ) {}

    public record EndpointPath(
            String httpMethod,
            String path,
            String className,
            String methodName,
            String shortestPath,
            String callType
    ) {}

    // ── Helpers ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildChainTree(CallChainResult chain) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("anchor", chain.anchorClass() + "." + chain.anchorMethod());

        List<Map<String, Object>> nodes = chain.classToSigs().entrySet().stream()
                .map(entry -> {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("className", entry.getKey());
                    node.put("methods", List.copyOf(entry.getValue()));
                    return node;
                })
                .collect(Collectors.toList());
        tree.put("nodes", nodes);
        return tree;
    }

    private String computeShortestPath(CallChainResult chain, String targetClass,
                                        String targetMethod, ApiEndpointDetector.ApiEndpoint endpoint) {
        String targetSig = targetClass + "." + targetMethod;
        String endpointSig = endpoint.className() + "." + endpoint.methodName();

        java.util.ArrayList<String> pathSegments = new java.util.ArrayList<>();
        pathSegments.add(formatMethodRef(endpoint.className(), endpoint.methodName()));

        String current = endpointSig;
        int maxSteps = 100;
        while (!current.equals(targetSig) && maxSteps-- > 0) {
            String next = chain.parentMap().get(current);
            if (next == null) break;
            String cls = CallChainRepository.extractClassName(next);
            String method = extractMethodName(next);
            pathSegments.add(formatMethodRef(cls, method));
            current = next;
        }

        if (pathSegments.size() <= 1) {
            return formatMethodRef(endpoint.className(), endpoint.methodName())
                    + " → ... → "
                    + formatMethodRef(targetClass, targetMethod);
        }

        return String.join(" → ", pathSegments);
    }

    private String formatMethodRef(String className, String methodName) {
        String simpleName = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1)
                : className;
        return simpleName + "." + methodName + "()";
    }

    private String extractMethodName(String signature) {
        int parenIdx = signature.indexOf('(');
        if (parenIdx < 0) return signature;
        int dotIdx = signature.lastIndexOf('.', parenIdx - 1);
        if (dotIdx < 0) return signature.substring(0, parenIdx);
        return signature.substring(dotIdx + 1, parenIdx);
    }
}
