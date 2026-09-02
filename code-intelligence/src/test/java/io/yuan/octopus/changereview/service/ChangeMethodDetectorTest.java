package io.yuan.octopus.changereview.service;

import io.yuan.octopus.changereview.dto.ChangedMethod;
import io.yuan.octopus.domain.FileLineChanges;
import io.yuan.octopus.entity.ClassInfo;
import io.yuan.octopus.entity.MethodInfo;
import io.yuan.octopus.repository.ClassInfoRepository;
import io.yuan.octopus.repository.MethodInfoRepository;
import io.yuan.octopus.service.GitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChangeMethodDetectorTest {

    @Mock private GitService gitService;
    @Mock private ClassInfoRepository classInfoRepository;
    @Mock private MethodInfoRepository methodInfoRepository;

    private static final String BASE = "abc123";
    private static final String PATCHSET = "def456";
    private static final String MERGE = "ghi789";
    private static final Path LOCAL = Path.of("/data/repos/test-repo");

    @Test
    void shouldIdentifyAddedMethodsFromNewFile() {
        FileLineChanges addFile = new FileLineChanges(
                "/data/repos/test-repo/src/NewClass.java", null, "ADD", List.of());
        when(gitService.computeChangedLineRanges(LOCAL, BASE, PATCHSET))
                .thenReturn(List.of(addFile));

        ClassInfo cls = buildClass(1L, 10L, "com.NewClass", addFile.filePath());
        MethodInfo m = buildMethod(1L, cls, "newMethod", "com.NewClass.newMethod()", 10, 15);
        when(classInfoRepository.findByCommitIdAndSourceFilePath(MERGE, addFile.filePath()))
                .thenReturn(List.of(cls));
        when(methodInfoRepository.findByClassInfoIdIn(List.of(1L)))
                .thenReturn(List.of(m));

        ChangeMethodDetector detector = new ChangeMethodDetector(gitService, classInfoRepository, methodInfoRepository);
        List<ChangedMethod> result = detector.detect(LOCAL, BASE, PATCHSET, MERGE);

        assertEquals(1, result.size());
        ChangedMethod cm = result.get(0);
        assertEquals("ADDED", cm.changeType());
        assertEquals("com.NewClass.newMethod()", cm.signature());
        assertEquals("com.NewClass", cm.qualifiedClassName());
        assertEquals(10L, cm.projectId());
    }

    @Test
    void shouldIdentifyModifiedMethodsByLineRange() {
        List<int[]> ranges = List.of(new int[]{10, 20}); // lines 10-20 changed
        FileLineChanges modFile = new FileLineChanges(
                "/data/repos/test-repo/src/ModClass.java", null, "MODIFY", ranges);
        when(gitService.computeChangedLineRanges(LOCAL, BASE, PATCHSET))
                .thenReturn(List.of(modFile));

        ClassInfo cls = buildClass(1L, 10L, "com.ModClass", modFile.filePath());
        MethodInfo intersecting = buildMethod(1L, cls, "intersecting", "sig_inter", 5, 15); // overlaps [10,20]
        MethodInfo notIntersecting = buildMethod(2L, cls, "outside", "sig_out", 21, 25);   // outside [10,20]
        when(classInfoRepository.findByCommitIdAndSourceFilePath(MERGE, modFile.filePath()))
                .thenReturn(List.of(cls));
        when(methodInfoRepository.findByClassInfoIdIn(List.of(1L)))
                .thenReturn(List.of(intersecting, notIntersecting));

        ChangeMethodDetector detector = new ChangeMethodDetector(gitService, classInfoRepository, methodInfoRepository);
        List<ChangedMethod> result = detector.detect(LOCAL, BASE, PATCHSET, MERGE);

        assertEquals(1, result.size());
        assertEquals("sig_inter", result.get(0).signature());
        assertEquals("MODIFIED", result.get(0).changeType());
    }

    @Test
    void shouldDetectDeletedMethodsWhenBaseAnalyzed() {
        FileLineChanges delFile = new FileLineChanges(
                "/data/repos/test-repo/src/Deleted.java", "/data/repos/test-repo/src/Deleted.java",
                "DELETE", List.of());
        when(gitService.computeChangedLineRanges(LOCAL, BASE, PATCHSET))
                .thenReturn(List.of(delFile));

        ClassInfo baseCls = buildClass(1L, 10L, "com.Deleted", delFile.filePath());
        MethodInfo oldM = buildMethod(1L, baseCls, "oldFunc", "sig_old", 5, 15);
        when(classInfoRepository.findByCommitIdAndSourceFilePath(BASE, delFile.filePath()))
                .thenReturn(List.of(baseCls));
        when(methodInfoRepository.findByClassInfoIdIn(List.of(1L)))
                .thenReturn(List.of(oldM));

        ChangeMethodDetector detector = new ChangeMethodDetector(gitService, classInfoRepository, methodInfoRepository);
        List<ChangedMethod> result = detector.detect(LOCAL, BASE, PATCHSET, MERGE);

        assertEquals(1, result.size());
        assertEquals("DELETED", result.get(0).changeType());
        assertEquals("sig_old", result.get(0).signature());
    }

    @Test
    void shouldSkipDeletedWhenBaseNotAnalyzed() {
        FileLineChanges delFile = new FileLineChanges(
                "/data/repos/test-repo/src/Deleted.java", null, "DELETE", List.of());
        when(gitService.computeChangedLineRanges(LOCAL, BASE, PATCHSET))
                .thenReturn(List.of(delFile));
        when(classInfoRepository.findByCommitIdAndSourceFilePath(BASE, delFile.filePath()))
                .thenReturn(List.of()); // base version not available

        ChangeMethodDetector detector = new ChangeMethodDetector(gitService, classInfoRepository, methodInfoRepository);
        List<ChangedMethod> result = detector.detect(LOCAL, BASE, PATCHSET, MERGE);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleEmptyChanges() {
        when(gitService.computeChangedLineRanges(LOCAL, BASE, PATCHSET)).thenReturn(List.of());

        ChangeMethodDetector detector = new ChangeMethodDetector(gitService, classInfoRepository, methodInfoRepository);
        List<ChangedMethod> result = detector.detect(LOCAL, BASE, PATCHSET, MERGE);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleMultipleFiles() {
        FileLineChanges addFile = new FileLineChanges("/f/New.java", null, "ADD", List.of());
        FileLineChanges modFile = new FileLineChanges("/f/Mod.java", null, "MODIFY",
                List.of(new int[]{5, 8}));
        when(gitService.computeChangedLineRanges(LOCAL, BASE, PATCHSET))
                .thenReturn(List.of(addFile, modFile));

        ClassInfo clsNew = buildClass(1L, 10L, "com.New", addFile.filePath());
        MethodInfo mNew = buildMethod(1L, clsNew, "mNew", "sig_new", 1, 10);
        ClassInfo clsMod = buildClass(2L, 10L, "com.Mod", modFile.filePath());
        MethodInfo mMod = buildMethod(2L, clsMod, "mMod", "sig_mod", 3, 7); // intersects [5,8]

        when(classInfoRepository.findByCommitIdAndSourceFilePath(MERGE, addFile.filePath()))
                .thenReturn(List.of(clsNew));
        when(classInfoRepository.findByCommitIdAndSourceFilePath(MERGE, modFile.filePath()))
                .thenReturn(List.of(clsMod));
        when(methodInfoRepository.findByClassInfoIdIn(List.of(1L))).thenReturn(List.of(mNew));
        when(methodInfoRepository.findByClassInfoIdIn(List.of(2L))).thenReturn(List.of(mMod));

        ChangeMethodDetector detector = new ChangeMethodDetector(gitService, classInfoRepository, methodInfoRepository);
        List<ChangedMethod> result = detector.detect(LOCAL, BASE, PATCHSET, MERGE);

        assertEquals(2, result.size());
        assertEquals(1, result.stream().filter(m -> "ADDED".equals(m.changeType())).count());
        assertEquals(1, result.stream().filter(m -> "MODIFIED".equals(m.changeType())).count());
    }

    // ── helpers ──

    private ClassInfo buildClass(Long id, Long projectId, String qname, String filePath) {
        ClassInfo c = ClassInfo.builder().id(id).projectId(projectId)
                .qualifiedName(qname).sourceFilePath(filePath)
                .isHead(true).commitId(MERGE).branch("main").build();
        return c;
    }

    private MethodInfo buildMethod(Long id, ClassInfo cls, String name, String sig, int start, int end) {
        return MethodInfo.builder().id(id).classInfo(cls)
                .methodName(name).methodSignature(sig)
                .lineStart(start).lineEnd(end)
                .filePath(cls.getSourceFilePath())
                .commitId(MERGE).isHead(true).build();
    }
}
