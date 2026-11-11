package io.yuan.octopus.service;

import io.yuan.octopus.common.SignatureNormalizer;
import io.yuan.octopus.domain.FileLineChanges;
import io.yuan.octopus.dto.response.EndpointDTO;
import io.yuan.octopus.entity.AnalyzedProjectEntity;
import io.yuan.octopus.entity.ClassInfo;
import io.yuan.octopus.entity.MethodInfo;
import io.yuan.octopus.repository.AnalysisTaskRepository;
import io.yuan.octopus.repository.AnalysisTaskStepRepository;
import io.yuan.octopus.repository.AnalyzedProjectRepository;
import io.yuan.octopus.repository.ClassInfoRepository;
import io.yuan.octopus.repository.MethodInfoRepository;

import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;

/** Two-sided impact evidence for business testing. No LLM and no unbounded path enumeration. */
@Service
public class BusinessTestImpactService {
    private static final int MAX_GRAPH_ITEMS = 100_000;
    private final AnalyzedProjectRepository projects;
    private final ClassInfoRepository classes;
    private final MethodInfoRepository methods;
    private final GitService git;
    private final QueryService query;
    private final Driver driver;
    private final AnalysisTaskRepository tasks;
    private final AnalysisTaskStepRepository taskSteps;
    private final RepositoryScopeService scopes;

    public BusinessTestImpactService(
            AnalyzedProjectRepository projects,
            ClassInfoRepository classes,
            MethodInfoRepository methods,
            GitService git,
            QueryService query,
            Driver driver,
            AnalysisTaskRepository tasks,
            AnalysisTaskStepRepository taskSteps, RepositoryScopeService scopes) {
        this.projects = projects;
        this.classes = classes;
        this.methods = methods;
        this.git = git;
        this.query = query;
        this.driver = driver;
        this.tasks = tasks;
        this.taskSteps = taskSteps;
        this.scopes = scopes;
    }

    public record Request(
            List<Long> projectIds, String branch, String baseCommitId, String targetCommitId, String repositoryKey) {
        public Request(List<Long> projectIds, String branch, String baseCommitId, String targetCommitId) {
            this(projectIds, branch, baseCommitId, targetCommitId, null);
        }
    }

    public record MethodRef(Long projectId, String branch, String commitId, String signature) {}

    public record CallEvidence(
            MethodRef caller, MethodRef callee, String callType, int lineNumber) {}

    public record Evidence(
            String id,
            String side,
            String changeType,
            String filePath,
            List<int[]> ranges,
            MethodRef changedMethod,
            EndpointDTO endpoint,
            List<CallEvidence> path) {}

    public record Report(
            List<Long> projectIds,
            String branch,
            String baseCommitId,
            String targetCommitId,
            String coverage,
            List<String> warnings,
            List<Evidence> evidence) {}

    record Seed(MethodRef method, String changeType, String file, List<int[]> ranges) {}

    private record Snapshot(
            Map<String, MethodRef> nodes, Map<String, List<CallEvidence>> callers) {}

    public Report analyze(Request request) {
        if (!blank(request.repositoryKey())) {
            request = new Request(scopes.resolve(request.repositoryKey(), request.branch(), request.projectIds()),
                    request.branch(), request.baseCommitId(), request.targetCommitId());
        }
        return analyzeModules(request);
    }

    private Report analyzeModules(Request request) {
        if (request.projectIds() == null
                || request.projectIds().isEmpty()
                || request.projectIds().stream().anyMatch(Objects::isNull)
                || request.projectIds().size() > 100
                || blank(request.branch())
                || !sha(request.baseCommitId())
                || !sha(request.targetCommitId())) {
            throw new IllegalArgumentException(
                    "projectIds, branch and full base/target commit SHAs are required");
        }
        List<Long> ids = request.projectIds().stream().distinct().sorted().toList();
        List<AnalyzedProjectEntity> selected = projects.findAllById(ids);
        if (selected.size() != ids.size())
            throw new IllegalArgumentException("Unknown analysis project");
        Path root = null;
        for (var project : selected) {
            Path candidate = repositoryRoot(project.getProjectPath());
            if (root != null && !root.equals(candidate))
                throw new IllegalArgumentException("Impact scope must use one repository");
            root = candidate;
        }
        List<FileLineChanges> changes =
                git.computeChangedLineRanges(
                        root, request.baseCommitId(), request.targetCommitId());
        List<String> warnings = new ArrayList<>();
        git.computeChangedPaths(root, request.baseCommitId(), request.targetCommitId()).stream()
                .filter(p -> !p.endsWith(".java") && !p.endsWith(".go"))
                .forEach(p -> warnings.add("UNSUPPORTED_CHANGED_FILE:" + p));
        List<Evidence> evidence = new ArrayList<>();
        for (String side : List.of("BASE", "TARGET")) {
            String commit = side.equals("BASE") ? request.baseCommitId() : request.targetCommitId();
            boolean old = side.equals("BASE");
            var analysis =
                    tasks.findByCommitIdAndBranchOrderByIdDesc(commit, request.branch()).stream()
                            .filter(
                                    t ->
                                            Objects.equals(
                                                    t.getRepoConfigId(),
                                                    selected.getFirst().getRepoConfigId()))
                            .findFirst();
            if (analysis.isEmpty() || !"COMPLETED".equals(analysis.get().getStatus())) {
                warnings.add("SNAPSHOT_READINESS_UNVERIFIED:" + commit);
            } else if (taskSteps.findByTaskIdOrderByIdAsc(analysis.get().getId()).stream()
                    .anyMatch(
                            s ->
                                    "DEGRADED".equals(s.getStatus())
                                            || "FAILED".equals(s.getStatus()))) {
                warnings.add("ANALYSIS_DEGRADED:" + commit);
            }
            Snapshot graph = snapshot(ids, request.branch(), commit, warnings);
            List<Seed> seeds = new ArrayList<>();
            Set<String> mappedFiles = new HashSet<>();
            Map<String, List<EndpointDTO>> endpoints = new TreeMap<>();
            for (Long id : ids) {
                List<ClassInfo> versionClasses =
                        classes.findByProjectIdAndBranchAndCommitId(id, request.branch(), commit);
                if (versionClasses.isEmpty()) warnings.add("METADATA_MISSING:" + id + ":" + commit);
                List<MethodInfo> versionMethods =
                        versionClasses.isEmpty()
                                ? List.of()
                                : methods.findByClassInfoIdIn(
                                        versionClasses.stream().map(ClassInfo::getId).toList());
                for (var endpoint : query.getEndpoints(id, request.branch(), commit)) {
                    endpoints
                            .computeIfAbsent(key(id, endpoint.signature()), k -> new ArrayList<>())
                            .add(endpoint);
                }
                for (var file : changes) {
                    if (old && "ADD".equals(file.changeType())
                            || !old && "DELETE".equals(file.changeType())) continue;
                    String filePath =
                            old && file.oldFilePath() != null
                                    ? file.oldFilePath()
                                    : file.filePath();
                    List<int[]> ranges = old ? file.oldLineRanges() : file.changedLineRanges();
                    List<MethodInfo> inFile =
                            versionMethods.stream()
                                    .filter(
                                            m ->
                                                    sameFile(
                                                            filePath,
                                                            m.getFilePath() != null
                                                                    ? m.getFilePath()
                                                                    : m.getClassInfo()
                                                                            .getSourceFilePath()))
                                    .toList();
                    if (inFile.isEmpty()) {
                        continue;
                    }
                    mappedFiles.add(filePath);
                    boolean wholeFile =
                            Set.of("ADD", "DELETE", "RENAME").contains(file.changeType());
                    List<MethodInfo> touched =
                            inFile.stream().filter(m -> wholeFile || overlaps(m, ranges)).toList();
                    // Changes outside method coordinates (annotations, fields, enum values)
                    // conservatively seed the file.
                    if (touched.isEmpty() && !ranges.isEmpty()) touched = inFile;
                    for (var m : touched) {
                        MethodRef ref =
                                new MethodRef(
                                        id,
                                        request.branch(),
                                        commit,
                                        SignatureNormalizer.canonical(m.getMethodSignature()));
                        if (!graph.nodes().containsKey(key(ref)))
                            warnings.add("METHOD_NOT_IN_GRAPH:" + key(ref) + ":" + commit);
                        seeds.add(new Seed(ref, file.changeType(), filePath, ranges));
                    }
                }
            }
            for (var file : changes) {
                if (old && "ADD".equals(file.changeType())
                        || !old && "DELETE".equals(file.changeType())) continue;
                String filePath =
                        old && file.oldFilePath() != null ? file.oldFilePath() : file.filePath();
                if (!mappedFiles.contains(filePath))
                    warnings.add("UNMAPPED_CHANGED_FILE:" + side + ":" + filePath);
            }
            for (Seed seed : seeds) evidence.addAll(trace(seed, side, graph.callers(), endpoints));
            var afterRead =
                    tasks.findByCommitIdAndBranchOrderByIdDesc(commit, request.branch()).stream()
                            .filter(
                                    t ->
                                            Objects.equals(
                                                    t.getRepoConfigId(),
                                                    selected.getFirst().getRepoConfigId()))
                            .findFirst();
            if (analysis.isPresent()
                    && (afterRead.isEmpty()
                            || !Objects.equals(analysis.get().getId(), afterRead.get().getId())
                            || !"COMPLETED".equals(afterRead.get().getStatus())))
                warnings.add("SNAPSHOT_CHANGED_DURING_READ:" + commit);
        }
        return new Report(
                ids,
                request.branch(),
                request.baseCommitId(),
                request.targetCommitId(),
                warnings.isEmpty() ? "STATIC_GRAPH" : "PARTIAL",
                warnings.stream().distinct().sorted().toList(),
                evidence.stream().sorted(Comparator.comparing(Evidence::id)).toList());
    }

    static List<Evidence> trace(
            Seed seed,
            String side,
            Map<String, List<CallEvidence>> callers,
            Map<String, List<EndpointDTO>> endpoints) {
        Map<String, CallEvidence> towardChange = new HashMap<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<MethodRef> queue = new ArrayDeque<>();
        visited.add(key(seed.method()));
        queue.add(seed.method());
        List<Evidence> found = new ArrayList<>();
        while (!queue.isEmpty()) {
            MethodRef current = queue.remove();
            List<CallEvidence> path = new ArrayList<>();
            if (endpoints.containsKey(key(current))) {
                MethodRef cursor = current;
                while (towardChange.containsKey(key(cursor))) {
                    CallEvidence edge = towardChange.get(key(cursor));
                    path.add(edge);
                    cursor = edge.callee();
                }
            }
            for (var endpoint : endpoints.getOrDefault(key(current), List.of())) {
                String identity =
                        side
                                + ":"
                                + seed.method().commitId()
                                + ":"
                                + key(seed.method())
                                + ":"
                                + key(current)
                                + ":"
                                + endpoint.httpMethod()
                                + ":"
                                + endpoint.path()
                                + ":"
                                + path
                                + ":"
                                + seed.changeType()
                                + ":"
                                + Arrays.deepToString(seed.ranges().toArray());
                found.add(
                        new Evidence(
                                UUID.nameUUIDFromBytes(
                                                identity.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8))
                                        .toString(),
                                side,
                                seed.changeType(),
                                seed.file(),
                                seed.ranges(),
                                seed.method(),
                                endpoint,
                                path));
            }
            for (CallEvidence edge : callers.getOrDefault(key(current), List.of())) {
                if (!visited.add(key(edge.caller()))) continue;
                towardChange.put(key(edge.caller()), edge);
                queue.add(edge.caller());
            }
        }
        return found;
    }

    private Snapshot snapshot(List<Long> ids, String branch, String commit, List<String> warnings) {
        Map<String, MethodRef> nodes = new HashMap<>();
        Map<String, List<CallEvidence>> callers = new HashMap<>();
        Map<String, Object> params =
                Map.of(
                        "ids",
                        ids,
                        "branch",
                        branch,
                        "commit",
                        commit,
                        "limit",
                        MAX_GRAPH_ITEMS + 1);
        try (var session = driver.session()) {
            var result =
                    session.run(
                            """
                            MATCH (m:MethodNode) WHERE m.projectId IN $ids AND m.branch=$branch AND m.commitId=$commit
                            RETURN m.projectId AS pid, m.signature AS sig ORDER BY pid,sig LIMIT $limit
                            """,
                            params);
            while (result.hasNext()) {
                var row = result.next();
                MethodRef ref =
                        new MethodRef(
                                row.get("pid").asLong(), branch, commit, row.get("sig").asString());
                nodes.put(key(ref), ref);
            }
            if (nodes.size() > MAX_GRAPH_ITEMS)
                throw new IllegalStateException("Graph exceeds impact node budget; narrow scope");
            for (Long id : ids)
                if (nodes.values().stream().noneMatch(n -> n.projectId().equals(id)))
                    warnings.add("GRAPH_MISSING:" + id + ":" + commit);
            result =
                    session.run(
                            """
                            MATCH (a:MethodNode)-[r:CALLS]->(b:MethodNode)
                            WHERE a.projectId IN $ids AND b.projectId IN $ids
                              AND a.branch=$branch AND b.branch=$branch AND r.branch=$branch
                              AND a.commitId=$commit AND b.commitId=$commit AND r.commitId=$commit
                              AND coalesce(r.callType,'DIRECT') <> 'SPRING_DI'
                            RETURN a.projectId AS ap,a.signature AS asig,b.projectId AS bp,b.signature AS bsig,
                                   coalesce(r.callType,'DIRECT') AS kind,coalesce(r.lineNumber,-1) AS line
                            ORDER BY ap,asig,bp,bsig,kind,line LIMIT $limit
                            """,
                            params);
            int count = 0;
            while (result.hasNext()) {
                if (++count > MAX_GRAPH_ITEMS)
                    throw new IllegalStateException(
                            "Graph exceeds impact edge budget; narrow scope");
                var row = result.next();
                MethodRef a =
                        new MethodRef(
                                row.get("ap").asLong(), branch, commit, row.get("asig").asString());
                MethodRef b =
                        new MethodRef(
                                row.get("bp").asLong(), branch, commit, row.get("bsig").asString());
                callers.computeIfAbsent(key(b), k -> new ArrayList<>())
                        .add(
                                new CallEvidence(
                                        a, b, row.get("kind").asString(), row.get("line").asInt()));
            }
        }
        return new Snapshot(nodes, callers);
    }

    private static boolean sameFile(String first, String second) {
        if (first == null || second == null) return false;
        return normalizedFile(Path.of(first)).equals(normalizedFile(Path.of(second)));
    }

    private static Path normalizedFile(Path input) {
        Path file = input.toAbsolutePath().normalize(), ancestor = file;
        try {
            while (ancestor != null && !java.nio.file.Files.exists(ancestor))
                ancestor = ancestor.getParent();
            return ancestor == null
                    ? file
                    : ancestor.toRealPath().resolve(ancestor.relativize(file)).normalize();
        } catch (java.io.IOException e) {
            return file;
        }
    }

    private static boolean overlaps(MethodInfo method, List<int[]> ranges) {
        if (method.getLineStart() == null || method.getLineEnd() == null) return true;
        return ranges.stream()
                .anyMatch(r -> method.getLineStart() <= r[1] && method.getLineEnd() >= r[0]);
    }

    private static Path repositoryRoot(String path) {
        try (var repo = new FileRepositoryBuilder().findGitDir(Path.of(path).toFile()).build()) {
            return repo.getWorkTree().toPath().toRealPath();
        } catch (Exception e) {
            throw new IllegalArgumentException("Project has no accessible Git repository", e);
        }
    }

    private static String key(MethodRef ref) {
        return key(ref.projectId(), ref.signature());
    }

    private static String key(Long projectId, String signature) {
        return projectId + ":" + SignatureNormalizer.canonical(signature);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean sha(String value) {
        return value != null && value.matches("[a-fA-F0-9]{40}");
    }
}
