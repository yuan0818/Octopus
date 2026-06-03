package io.yuan.octopus.dto.response;

import java.util.List;

public record ClassDetailDTO(
    Long id,
    String qualifiedName,
    String simpleName,
    String classType,
    String modifiers,
    String sourceFilePath,
    String parentClass,
    List<String> interfaces,
    List<MethodDetailDTO> methods,
    List<FieldDetailDTO> fields,
    List<AnnotationDetailDTO> annotations,
    List<InheritanceDetailDTO> inheritanceRelations,
    List<ExternalDependencyDTO> externalDependencies
) {}
