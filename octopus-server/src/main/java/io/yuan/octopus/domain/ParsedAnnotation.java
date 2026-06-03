package io.yuan.octopus.domain;

import java.util.Map;

public record ParsedAnnotation(
    String name,
    Map<String, String> parameters
) {}
