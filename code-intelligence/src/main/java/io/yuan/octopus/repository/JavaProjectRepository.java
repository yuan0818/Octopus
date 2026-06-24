package io.yuan.octopus.repository;

import io.yuan.octopus.entity.JavaProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JavaProjectRepository extends JpaRepository<JavaProjectEntity, Long> {

    void deleteByRepoConfigId(Long repoConfigId);

    List<JavaProjectEntity> findByRepoConfigId(Long repoConfigId);

    /** Find all projects for a specific repo + branch combination. */
    List<JavaProjectEntity> findByRepoConfigIdAndBranch(Long repoConfigId, String branch);

    /** Locate a single project by its unique (repoConfigId, branch, projectName) key. */
    Optional<JavaProjectEntity> findByRepoConfigIdAndBranchAndProjectName(
            Long repoConfigId, String branch, String projectName);

    /**
     * Returns the latest successfully analysed project row for a repo+branch.
     * Used to derive a deterministic branch-level baseline tag for sync-time diff.
     */
    Optional<JavaProjectEntity> findFirstByRepoConfigIdAndBranchAndAnalyzedTagIsNotNullOrderByAnalyzedAtDescIdDesc(
            Long repoConfigId, String branch);

    /**
     * Finds a local (repoConfigId=null) project by its canonical project path.
     * Used to deduplicate local projects that share the same name but differ in location.
     */
    Optional<JavaProjectEntity> findByRepoConfigIdIsNullAndProjectPath(String projectPath);
}
