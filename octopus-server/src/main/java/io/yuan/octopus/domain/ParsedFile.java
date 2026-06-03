package io.yuan.octopus.domain;

import java.util.List;

public record ParsedFile(
    String filePath,
    String packageName,
    List<String> imports,
    List<ParsedClass> classes
) {}
