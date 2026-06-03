package io.yuan.octopus.domain;

public record ParseError(
    String filePath,
    String errorMessage
) {}
