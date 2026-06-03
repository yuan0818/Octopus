package io.yuan.octopus.domain;

import java.util.List;

public record ParsedField(
    String name,
    String type,
    List<String> modifiers
) {}
