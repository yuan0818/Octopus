package io.yuan.octopus.dto.response;

public record AnnotatedElementDTO(
    String annotationName,
    String elementType,
    String elementName,
    String qualifiedClassName
) {}
