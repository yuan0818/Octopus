package io.yuan.octopus.service;

import io.yuan.octopus.entity.AnalyzedProjectEntity;
import io.yuan.octopus.repository.AnalyzedProjectRepository;
import io.yuan.octopus.repository.ClassInfoRepository;
import io.yuan.octopus.repository.RepoConfigRepository;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** A repository is a selection scope; module IDs remain the owners of graph/metadata. */
@Service
public class RepositoryScopeService {
    private final AnalyzedProjectRepository projects;
    private final ClassInfoRepository classes;
    private final RepoConfigRepository repositories;

    public RepositoryScopeService(AnalyzedProjectRepository projects, ClassInfoRepository classes,
                                  RepoConfigRepository repositories) {
        this.projects = projects;
        this.classes = classes;
        this.repositories = repositories;
    }

    public record Module(Long projectId, String name, String modulePath, String branch, List<String> branches) {}
    public record Scope(String key, String name, String rootPath, List<Module> modules) {}
    public record Version(String commitId, List<Long> projectIds, List<Long> missingProjectIds) {}

    public static String root(AnalyzedProjectEntity project) {
        return project.getRepositoryRootPath() != null ? project.getRepositoryRootPath()
                : RepositoryIdentity.root(project.getProjectPath());
    }

    public static String key(AnalyzedProjectEntity project) {
        if (project.getRepoConfigId() != null) return "repo:" + project.getRepoConfigId();
        String root = root(project);
        return root == null ? "project:" + project.getId() : "local:" + Base64.getUrlEncoder()
                .withoutPadding().encodeToString(root.getBytes(StandardCharsets.UTF_8));
    }

    public static String modulePath(AnalyzedProjectEntity project) {
        String root = root(project);
        if (root == null || project.getProjectPath() == null) return project.getProjectName();
        Path base = RepositoryIdentity.canonicalPath(root);
        Path module = RepositoryIdentity.canonicalPath(project.getProjectPath());
        if (!module.startsWith(base)) return project.getProjectName();
        String relative = base.relativize(module).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }

    public List<Scope> scopes() {
        Map<String, List<AnalyzedProjectEntity>> groups = new TreeMap<>();
        projects.findAll().forEach(p -> groups.computeIfAbsent(key(p), k -> new ArrayList<>()).add(p));
        return groups.entrySet().stream().map(entry -> {
            var members = entry.getValue();
            var first = members.getFirst();
            String root = root(first);
            String name = root == null ? first.getProjectName() : Path.of(root).getFileName().toString();
            if (first.getRepoConfigId() != null) {
                // Only the repository basename is exposed; URLs may contain credentials.
                name = repositories.findById(first.getRepoConfigId()).map(r -> {
                    String url = r.getRepoUrl().replaceAll("[?#].*$", "").replaceAll("/+$", "");
                    return url.substring(Math.max(url.lastIndexOf('/'), url.lastIndexOf(':')) + 1)
                            .replaceFirst("\\.git$", "");
                }).orElse(name);
            }
            return new Scope(entry.getKey(), name, root, members.stream()
                    .map(p -> new Module(p.getId(), p.getProjectName(), modulePath(p), p.getBranch(),
                            moduleBranches(p)))
                    .sorted(Comparator.comparing(Module::modulePath).thenComparing(Module::projectId)).toList());
        }).sorted(Comparator.comparing(Scope::name).thenComparing(Scope::key)).toList();
    }

    private List<AnalyzedProjectEntity> members(String key) {
        var result = projects.findAll().stream().filter(p -> key(p).equals(key)).toList();
        if (result.isEmpty()) throw new IllegalArgumentException("Unknown repository scope");
        return result;
    }

    public List<String> branches(String key) {
        return members(key).stream().flatMap(p -> moduleBranches(p).stream())
                .distinct().sorted().toList();
    }

    private List<String> moduleBranches(AnalyzedProjectEntity project) {
        Set<String> branches = new TreeSet<>();
        classes.findAnalyzedBranches(project.getId()).stream()
                .filter(b -> b != null && !b.isBlank()).forEach(branches::add);
        // Keep a registered module visible even if its analysis produced no metadata.
        if (project.getBranch() != null && !project.getBranch().isBlank()) branches.add(project.getBranch());
        return List.copyOf(branches);
    }

    public List<Long> resolve(String key, String branch, List<Long> requestedIds) {
        if (branch == null || branch.isBlank()) throw new IllegalArgumentException("Branch is required");
        // Repo-backed rows are branch-specific; local rows can retain several historical branches.
        var eligible = members(key).stream()
                .filter(p -> p.getRepoConfigId() == null
                        ? moduleBranches(p).contains(branch)
                        : branch.equals(p.getBranch()))
                .map(AnalyzedProjectEntity::getId).distinct().sorted().toList();
        if (eligible.isEmpty()) throw new IllegalArgumentException("No analyzed modules on this branch");
        if (requestedIds == null) return eligible;
        if (requestedIds.isEmpty() || requestedIds.stream().anyMatch(Objects::isNull) || !eligible.containsAll(requestedIds))
            throw new IllegalArgumentException("Selected modules must belong to this repository and branch");
        return requestedIds.stream().distinct().sorted().toList();
    }

    public List<Version> versions(String key, String branch, List<Long> requestedIds) {
        List<Long> ids = resolve(key, branch, requestedIds);
        Map<String, Set<Long>> versions = new LinkedHashMap<>();
        for (Long id : ids) {
            for (String commit : classes.findAnalyzedCommitIds(id, branch)) {
                if (commit != null && commit.matches("[0-9a-fA-F]{40}"))
                    versions.computeIfAbsent(commit, k -> new TreeSet<>()).add(id);
            }
        }
        return versions.entrySet().stream().map(e -> new Version(e.getKey(), List.copyOf(e.getValue()),
                ids.stream().filter(id -> !e.getValue().contains(id)).toList())).toList();
    }
}
