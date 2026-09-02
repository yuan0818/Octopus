package io.yuan.octopus.domain;

import io.yuan.octopus.common.BuildTool;
import io.yuan.octopus.common.JavaVersion;

import java.nio.file.Path;
import java.util.List;

public record DiscoveredProject(
    String projectName,
    Path projectPath,
    BuildTool buildTool,
    List<Path> sourceDirectories,
    JavaVersion javaVersion
) {}
