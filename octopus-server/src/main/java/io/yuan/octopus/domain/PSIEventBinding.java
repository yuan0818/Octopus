package io.yuan.octopus.domain;

import java.util.List;

/**
 * Spring event publisher-to-listener binding extracted by the PSI analyzer.
 */
public record PSIEventBinding(
    String eventType,
    List<String> publisherMethodSignatures,
    List<String> listenerMethodSignatures
) {}
