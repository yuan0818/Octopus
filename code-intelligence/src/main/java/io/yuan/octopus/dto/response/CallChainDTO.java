package io.yuan.octopus.dto.response;

import java.util.List;

/**
 * 调用链查询结果。
 *
 * @param truncated 调用树节点数超过 {@code octopus.query.call-chain.max-tree-nodes} 时为 true，
 *                  表示结果不完整（前端应提示用户缩小范围或指定 maxDepth）
 */
public record CallChainDTO(
    MethodNodeDTO root,
    List<CallChainNodeDTO> chain,
    boolean truncated
) {
    public record CallChainNodeDTO(
        MethodNodeDTO method,
        String callType,
        int lineNumber,
        List<CallChainNodeDTO> children
    ) {}
}
