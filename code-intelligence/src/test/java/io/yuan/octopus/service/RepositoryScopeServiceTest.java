package io.yuan.octopus.service;

import io.yuan.octopus.entity.AnalyzedProjectEntity;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.repository.*;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepositoryScopeServiceTest {
    @TempDir Path directory;
    final AnalyzedProjectRepository projects = mock(AnalyzedProjectRepository.class);
    final ClassInfoRepository classes = mock(ClassInfoRepository.class);
    final RepoConfigRepository repositories = mock(RepoConfigRepository.class);
    final RepositoryScopeService service = new RepositoryScopeService(projects, classes, repositories);

    AnalyzedProjectEntity local(long id, Path path) {
        return AnalyzedProjectEntity.builder().id(id).projectName(path.getFileName().toString())
                .projectPath(path.toString()).branch("main").build();
    }

    @Test void recoversLegacyRootWithoutCreatingAnExtraGraphProject() throws Exception {
        try (var git = Git.init().setDirectory(directory.toFile()).call()) {
            Path a = Files.createDirectories(directory.resolve("parent/api"));
            Path b = Files.createDirectories(directory.resolve("parent/common"));
            when(projects.findAll()).thenReturn(List.of(local(1, a), local(2, b)));
            var scopes = service.scopes();
            assertEquals(1, scopes.size());
            assertEquals(directory.toRealPath().toString(), scopes.getFirst().rootPath());
            assertEquals(List.of("parent/api", "parent/common"), scopes.getFirst().modules().stream()
                    .map(RepositoryScopeService.Module::modulePath).toList());
            verify(projects, never()).save(any());
        }
    }

    @Test void siblingDirectoriesWithoutRepositoryEvidenceStaySeparate() throws Exception {
        var first = local(1, Files.createDirectories(directory.resolve("a/common")));
        var second = local(2, Files.createDirectories(directory.resolve("b/common")));
        when(projects.findAll()).thenReturn(List.of(first, second));
        assertEquals(2, service.scopes().size());
    }

    @Test void persistedWorkspaceRootSurvivesMissingCheckout() {
        var p = local(1, directory.resolve("removed/module"));
        p.setRepositoryRootPath(directory.resolve("removed").toString());
        when(projects.findAll()).thenReturn(List.of(p));
        assertEquals("module", service.scopes().getFirst().modules().getFirst().modulePath());
        assertEquals("removed", service.scopes().getFirst().name());
    }

    @Test void wholeRepositoryUsesBranchRowsAndRejectsForeignOrEmptySubsets() {
        var mainA = local(1, directory.resolve("a")); mainA.setRepoConfigId(8L);
        var mainB = local(2, directory.resolve("b")); mainB.setRepoConfigId(8L);
        var releaseA = local(3, directory.resolve("a")); releaseA.setRepoConfigId(8L); releaseA.setBranch("release");
        var foreign = local(4, directory.resolve("other")); foreign.setRepoConfigId(9L);
        when(projects.findAll()).thenReturn(List.of(mainA, mainB, releaseA, foreign));
        assertEquals(List.of(1L, 2L), service.resolve("repo:8", "main", null));
        assertEquals(List.of(3L), service.resolve("repo:8", "release", null));
        assertEquals(List.of(2L), service.resolve("repo:8", "main", List.of(2L)));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("repo:8", "main", List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("repo:8", "main", List.of(4L)));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("repo:8", "main", List.of(3L)));
    }

    @Test void localHistoricalBranchDoesNotDependOnLatestDisplayBranch() {
        var a = local(1, directory.resolve("a")); a.setBranch("release"); a.setRepositoryRootPath(directory.toString());
        var b = local(2, directory.resolve("b")); b.setRepositoryRootPath(directory.toString());
        when(projects.findAll()).thenReturn(List.of(a, b));
        when(classes.findAnalyzedBranches(1L)).thenReturn(List.of("release", "main"));
        when(classes.findAnalyzedBranches(2L)).thenReturn(List.of("main"));
        String key = RepositoryScopeService.key(a);
        assertEquals(List.of(1L, 2L), service.resolve(key, "main", null));
        assertEquals(List.of(1L), service.resolve(key, "release", null));
    }

    @Test void versionsExposeUnionAndMissingModulesInsteadOfHidingPartialSnapshots() {
        var a = local(1, directory.resolve("a")); a.setRepoConfigId(8L);
        var b = local(2, directory.resolve("b")); b.setRepoConfigId(8L);
        when(projects.findAll()).thenReturn(List.of(a, b));
        String base = "a".repeat(40), target = "b".repeat(40);
        when(classes.findAnalyzedCommitIds(1L, "main")).thenReturn(List.of(target, base));
        when(classes.findAnalyzedCommitIds(2L, "main")).thenReturn(List.of(target));
        var versions = service.versions("repo:8", "main", null);
        assertEquals(List.of(1L, 2L), versions.getFirst().projectIds());
        assertTrue(versions.getFirst().missingProjectIds().isEmpty());
        assertEquals(base, versions.get(1).commitId());
        assertEquals(List.of(2L), versions.get(1).missingProjectIds());
    }

    @Test void repositoryDisplayNameDoesNotExposeUrlCredentials() {
        var a = local(1, directory.resolve("repo_8/a")); a.setRepoConfigId(8L);
        when(projects.findAll()).thenReturn(List.of(a));
        when(repositories.findById(8L)).thenReturn(Optional.of(RepoConfig.builder()
                .repoUrl("https://example-user:example-password@example.invalid/team/Platform.git?token=example-token")
                .build()));
        assertEquals("Platform", service.scopes().getFirst().name());
    }
}
