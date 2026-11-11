package io.yuan.octopus.entity;

import io.yuan.octopus.common.BuildTool;
import io.yuan.octopus.common.JavaVersion;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "analyzed_project")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyzedProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_config_id")
    private Long repoConfigId;

    /**
     * The Git branch this project record belongs to (e.g. {@code main}, {@code release/eu}).
     * Together with {@code repoConfigId} and {@code projectName} it forms a unique key,
     * allowing multiple branches of the same repository to coexist independently.
     */
    @Column(name = "branch", length = 200)
    private String branch;

    @Column(name = "project_name", length = 200)
    private String projectName;

    @Column(name = "project_path", length = 500)
    private String projectPath;

    /** Workspace / Git root retained even when the root has no compilable source. */
    @Column(name = "repository_root_path", length = 1000)
    private String repositoryRootPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "build_tool", length = 20)
    private BuildTool buildTool;

    @Enumerated(EnumType.STRING)
    @Column(name = "java_version", length = 10)
    private JavaVersion javaVersion;

    @Column(name = "commit_id", length = 100)
    private String commitId;

    /**
     * Whether this project is a Java backend project (i.e. compiled Java/Kotlin code,
     * not a pure frontend project).  Always {@code true} for projects analysed by octopus
     * because the analyser only accepts JVM projects.  Set after every successful analysis.
     */
    @Column(name = "is_backend_project")
    private Boolean isBackendProject;

    /**
     * Whether this project exposes HTTP API endpoints (detected from analysis results).
     * {@code true} when the project contains {@code @RestController} or {@code @Controller}
     * classes; {@code false} for backend libraries / utilities / scheduled-job modules.
     * Set after every successful analysis.
     */
    @Column(name = "is_api_service")
    private Boolean isApiService;

    /**
     * The Git tag name of the last successfully analysed version (e.g. {@code v1.2.0}).
     * Used as the {@code fromRef} when computing an incremental diff for the next analysis.
     */
    @Column(name = "analyzed_tag", length = 200)
    private String analyzedTag;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;
}
