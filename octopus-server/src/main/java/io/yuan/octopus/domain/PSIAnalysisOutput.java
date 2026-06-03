package io.yuan.octopus.domain;

import java.util.List;

/**
 * Root output object produced by the IntelliJ PSI headless analyzer subprocess.
 * Deserialized from the JSON file that octopus-psi writes to disk.
 */
public record PSIAnalysisOutput(
    List<PSIMethodNode> methods,
    List<PSICallEdge> edges,
    List<PSIBeanDefinition> beans,
    List<PSIAOPBinding> aopBindings,
    List<PSIEventBinding> eventBindings,
    List<PSIScheduledBinding> scheduledBindings,
    List<PSIAsyncBinding> asyncBindings,
    List<PSIRepositoryBinding> repositoryBindings
) {}
