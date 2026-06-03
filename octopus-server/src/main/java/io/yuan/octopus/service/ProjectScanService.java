package io.yuan.octopus.service;

import io.yuan.octopus.common.BuildTool;
import io.yuan.octopus.common.JavaVersion;
import io.yuan.octopus.domain.JavaProject;
import io.yuan.octopus.entity.RepoSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ProjectScanService {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanService.class);
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", ".gradle"
    );

    /**
     * Gradle/Maven sub-module directory names that indicate a test-only module.
     * Such modules typically contain integration tests or test infrastructure only
     * and should not contribute to the production call graph.
     */
    private static final Set<String> TEST_MODULE_EXACT_NAMES = Set.of("test", "tests", "testing");
    private static final List<String> TEST_MODULE_SUFFIXES =
            List.of("-test", "-tests", "_test", "_tests", "-testing");

    private final JavaVersionDetector javaVersionDetector;

    public ProjectScanService(JavaVersionDetector javaVersionDetector) {
        this.javaVersionDetector = javaVersionDetector;
    }

    public List<JavaProject> scanProjects(RepoSnapshot snapshot) {
        Path repoRoot = snapshot.repoLocalPath();
        if (!Files.isDirectory(repoRoot)) {
            return List.of();
        }

        List<JavaProject> projects = new ArrayList<>();

        try {
            Files.walkFileTree(repoRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (SKIP_DIRECTORIES.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    BuildTool buildTool = detectBuildTool(dir);
                    if (buildTool != null) {
                        if (isTestModule(dir)) {
                            log.debug("Skipping test sub-module: {}", dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        JavaProject project = createJavaProject(dir, buildTool);
                        projects.add(project);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Failed to visit file: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to scan projects in {}: {}", repoRoot, e.getMessage());
        }

        return projects;
    }

    private BuildTool detectBuildTool(Path dir) {
        if (Files.exists(dir.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))) {
            return BuildTool.GRADLE;
        }
        return null;
    }

    /**
     * Returns {@code true} if the directory name indicates a test-only sub-module,
     * e.g. {@code my-service-test}, {@code my-service-tests}, {@code test}, {@code tests}.
     * These are skipped so that test code does not pollute the production call graph.
     */
    private boolean isTestModule(Path dir) {
        String name = dir.getFileName().toString().toLowerCase();
        if (TEST_MODULE_EXACT_NAMES.contains(name)) {
            return true;
        }
        for (String suffix : TEST_MODULE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private JavaProject createJavaProject(Path dir, BuildTool buildTool) {
        String projectName = dir.getFileName().toString();
        Path srcMainJava = dir.resolve("src/main/java");
        List<Path> sourceDirectories = Files.isDirectory(srcMainJava)
                ? List.of(srcMainJava)
                : List.of();
        JavaVersion javaVersion = javaVersionDetector.detectVersion(dir, buildTool);

        return new JavaProject(projectName, dir, buildTool, sourceDirectories, javaVersion);
    }

    public JavaProject scanSingleProject(Path projectDir, String projectName) {
        BuildTool buildTool = detectBuildTool(projectDir);
        Path srcMainJava = projectDir.resolve("src/main/java");
        List<Path> sourceDirectories = Files.isDirectory(srcMainJava)
                ? List.of(srcMainJava)
                : List.of();
        JavaVersion javaVersion = javaVersionDetector.detectVersion(projectDir,
                buildTool != null ? buildTool : BuildTool.MAVEN);
        return new JavaProject(projectName, projectDir, buildTool, sourceDirectories, javaVersion);
    }
}
