package io.yuan.octopus.repository;

import io.yuan.octopus.entity.AnalyzedProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalyzedProjectRepository extends JpaRepository<AnalyzedProjectEntity, Long> {

    void deleteByRepoConfigId(Long repoConfigId);

    List<AnalyzedProjectEntity> findByRepoConfigId(Long repoConfigId);

    /** Find all projects for a specific repo + branch combination. */
    List<AnalyzedProjectEntity> findByRepoConfigIdAndBranch(Long repoConfigId, String branch);

    /** Locate a single project by its unique (repoConfigId, branch, projectName) key. */
    Optional<AnalyzedProjectEntity> findByRepoConfigIdAndBranchAndProjectName(
            Long repoConfigId, String branch, String projectName);

    /**
     * Returns the latest successfully analysed project row for a repo+branch.
     * Used to derive a deterministic branch-level baseline tag for sync-time diff.
     */
    Optional<AnalyzedProjectEntity> findFirstByRepoConfigIdAndBranchAndAnalyzedTagIsNotNullOrderByAnalyzedAtDescIdDesc(
            Long repoConfigId, String branch);

    /**
     * Finds a local (repoConfigId=null) project by its canonical project path.
     * Used to deduplicate local projects that share the same name but differ in location.
     */
    Optional<AnalyzedProjectEntity> findByRepoConfigIdIsNullAndProjectPath(String projectPath);

    /**
     * Finds a local (repoConfigId=null) project by canonical path AND branch —
     * the local counterpart of the repo-backed (repoConfigId, branch, projectName)
     * key, so re-analysing the same local path on different branches produces
     * independent rows that coexist instead of overwriting each other.
     */
    Optional<AnalyzedProjectEntity> findByRepoConfigIdIsNullAndProjectPathAndBranch(
            String projectPath, String branch);
}
