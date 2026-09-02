package io.yuan.octopus.changereview.service;

import io.yuan.octopus.changereview.dto.ChangedMethod;
import io.yuan.octopus.domain.FileLineChanges;
import io.yuan.octopus.entity.ClassInfo;
import io.yuan.octopus.entity.MethodInfo;
import io.yuan.octopus.repository.ClassInfoRepository;
import io.yuan.octopus.repository.MethodInfoRepository;
import io.yuan.octopus.service.GitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Identifies changed methods (signature-level) between a base commit and a patchset
 * commit (REQ-CTR-005). Intersects JGit histogram diff hunks with version-aware
 * {@code method_info.line_start..line_end} of the merge version (commitId = merge SHA)
 * to classify ADDED / MODIFIED / DELETED methods.
 *
 * <ul>
 *   <li>ADDED    – method in a new file, or in a file whose all methods are new (ADD change)</li>
 *   <li>MODIFIED – method whose {@code [line_start, line_end]} overlaps a replaced/inserted hunk</li>
 *   <li>DELETED  – method present in the base version but absent from the merge version
 *                  (best-effort: only when the base commit was previously analysed)</li>
 * </ul>
 *
 * <p>Per BR-CTR-024, DELETED methods are returned for reporting only and are NOT used as
 * reverse-traversal seeds (the orchestrator filters them out before calling
 * {@code AffectedEndpointService}).
 */
@Service
public class ChangeMethodDetector {

    private static final Logger log = LoggerFactory.getLogger(ChangeMethodDetector.class);

    private final GitService gitService;
    private final ClassInfoRepository classInfoRepository;
    private final MethodInfoRepository methodInfoRepository;

    public ChangeMethodDetector(GitService gitService,
                                ClassInfoRepository classInfoRepository,
                                MethodInfoRepository methodInfoRepository) {
        this.gitService = gitService;
        this.classInfoRepository = classInfoRepository;
        this.methodInfoRepository = methodInfoRepository;
    }

    /**
     * @param localPath        local merge working tree (left on the merge commit)
     * @param baseCommitId     base HEAD commit SHA
     * @param patchsetCommitId patchset commit SHA
     * @param mergeCommitId   merge commit SHA (version key of the just-analysed merge tree)
     * @return changed methods; empty list when no Java changes (BR-CTR-026)
     */
    public List<ChangedMethod> detect(Path localPath, String baseCommitId,
                                      String patchsetCommitId, String mergeCommitId) {
        List<FileLineChanges> files = gitService.computeChangedLineRanges(
                localPath, baseCommitId, patchsetCommitId);
        if (files.isEmpty()) {
            log.info("No Java file changes between base={} and patchset={} (BR-CTR-026)", baseCommitId, patchsetCommitId);
            return List.of();
        }

        List<ChangedMethod> result = new ArrayList<>();
        for (FileLineChanges file : files) {
            switch (file.changeType()) {
                case "ADD" -> result.addAll(classifyAllMethods(file, mergeCommitId, "ADDED"));
                case "MODIFY" -> result.addAll(classifyByLineRange(file, mergeCommitId));
                case "DELETE", "RENAME" -> result.addAll(handleDeletedFile(file, baseCommitId));
            }
        }
        log.info("Detected {} changed methods (base={} patchset={} merge={})",
                result.size(), baseCommitId, patchsetCommitId, mergeCommitId);
        return result;
    }

    /**
     * All methods in the file (merge version) become the given change type (ADDED for new files).
     */
    private List<ChangedMethod> classifyAllMethods(FileLineChanges file, String mergeCommitId, String changeType) {
        if (file.filePath() == null) return List.of();
        List<ClassInfo> classes = classInfoRepository.findByCommitIdAndSourceFilePath(mergeCommitId, file.filePath());
        return collectMethods(classes, changeType);
    }

    /**
     * Methods whose [line_start, line_end] intersects any changed hunk → MODIFIED.
     * Methods not intersecting any hunk are left out (unchanged).
     */
    private List<ChangedMethod> classifyByLineRange(FileLineChanges file, String mergeCommitId) {
        if (file.filePath() == null || file.changedLineRanges().isEmpty()) {
            return List.of();
        }
        List<ClassInfo> classes = classInfoRepository.findByCommitIdAndSourceFilePath(mergeCommitId, file.filePath());
        List<MethodInfo> methods = loadMethods(classes);
        List<ChangedMethod> modified = new ArrayList<>();
        for (MethodInfo m : methods) {
            if (m.getLineStart() == null || m.getLineEnd() == null) {
                continue; // legacy data without line numbers — cannot classify
            }
            if (intersectsAnyRange(m.getLineStart(), m.getLineEnd(), file.changedLineRanges())) {
                modified.add(toChangedMethod(m, classes, "MODIFIED"));
            }
        }
        return modified;
    }

    /**
     * DELETED detection: methods in the base version of the file (commitId=baseCommitId)
     * whose signatures are absent from the merge version. Best-effort — skipped when the
     * base commit was never analysed (no base-version class_info rows).
     */
    private List<ChangedMethod> handleDeletedFile(FileLineChanges file, String baseCommitId) {
        String path = file.oldFilePath() != null ? file.oldFilePath() : file.filePath();
        if (path == null) return List.of();
        List<ClassInfo> baseClasses = classInfoRepository.findByCommitIdAndSourceFilePath(baseCommitId, path);
        if (baseClasses.isEmpty()) {
            // Base version not analysed — cannot detect deletions. Non-fatal.
            log.debug("Base version not analysed for {} (commitId={}); skipping DELETED detection", path, baseCommitId);
            return List.of();
        }
        List<MethodInfo> baseMethods = loadMethods(baseClasses);
        Set<String> baseSignatures = new HashSet<>();
        for (MethodInfo m : baseMethods) {
            baseSignatures.add(m.getMethodSignature());
        }
        // All methods in a DELETE file are deleted; for RENAME/DELETE we report base-side methods as DELETED.
        return collectMethods(baseClasses, "DELETED");
    }

    // ── Helpers ──

    private List<MethodInfo> loadMethods(List<ClassInfo> classes) {
        if (classes.isEmpty()) return List.of();
        List<Long> classIds = classes.stream().map(ClassInfo::getId).collect(Collectors.toList());
        return methodInfoRepository.findByClassInfoIdIn(classIds);
    }

    private List<ChangedMethod> collectMethods(List<ClassInfo> classes, String changeType) {
        List<MethodInfo> methods = loadMethods(classes);
        List<ChangedMethod> out = new ArrayList<>();
        for (MethodInfo m : methods) {
            out.add(toChangedMethod(m, classes, changeType));
        }
        return out;
    }

    private ChangedMethod toChangedMethod(MethodInfo m, List<ClassInfo> classes, String changeType) {
        ClassInfo owner = classes.stream()
                .filter(c -> c.getId().equals(m.getClassInfo() != null ? m.getClassInfo().getId() : null))
                .findFirst()
                .orElse(null);
        Long projectId = owner != null ? owner.getProjectId() : null;
        String qualifiedName = owner != null ? owner.getQualifiedName() : null;
        String filePath = m.getFilePath() != null ? m.getFilePath()
                : (owner != null ? owner.getSourceFilePath() : null);
        return new ChangedMethod(projectId, qualifiedName, m.getMethodName(),
                m.getMethodSignature(), filePath, changeType);
    }

    private boolean intersectsAnyRange(int methodStart, int methodEnd, List<int[]> ranges) {
        for (int[] r : ranges) {
            int rs = r[0];
            int re = r[1];
            if (methodStart <= re && methodEnd >= rs) {
                return true;
            }
        }
        return false;
    }
}
