package io.yuan.octopus.domain;

import java.util.List;

/**
 * @Async method binding extracted by the PSI analyzer.
 * Maps an async method back to its caller(s) across the proxy boundary.
 */
public record PSIAsyncBinding(
    String asyncMethodSignature,
    List<String> callerMethodSignatures
) {}
