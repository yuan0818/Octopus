package io.yuan.octopus.domain;

import java.util.List;

/**
 * Spring Data repository method binding extracted by the PSI analyzer.
 * Maps repository interface methods to their Spring Data implementation methods.
 */
public record PSIRepositoryBinding(
    String repositoryInterfaceName,
    String domainClassName,
    List<String> generatedMethodSignatures,
    List<String> callerMethodSignatures
) {}
