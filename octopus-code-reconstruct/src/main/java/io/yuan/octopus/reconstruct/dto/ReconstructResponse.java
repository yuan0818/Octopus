package io.yuan.octopus.reconstruct.dto;

import java.util.List;
import java.util.Map;

public record ReconstructResponse(
    String anchor,
    Map<String, String> classes,
    List<String> involvedClasses,
    int totalMethods,
    int totalChars,
    boolean truncated,
    String sourceOrigin   // "git" | "mysql_fallback"; 表示源码来源
) {}
