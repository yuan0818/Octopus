package io.yuan.octopus.domain;

import java.util.List;

/**
 * Spring Bean definition extracted by the PSI analyzer.
 */
public record PSIBeanDefinition(
    String beanName,
    String beanClassName,
    List<String> interfaceNames,
    String scope,
    String configClassOrMethod
) {}
