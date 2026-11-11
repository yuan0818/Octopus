package io.yuan.octopus.service;

import io.yuan.octopus.entity.*;
import io.yuan.octopus.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EndpointCatalogVersionTest {
    private final ClassInfoRepository classes = mock(ClassInfoRepository.class);
    private final MethodInfoRepository methods = mock(MethodInfoRepository.class);
    private final AnnotationInfoRepository annotations = mock(AnnotationInfoRepository.class);
    private final AnalyzedProjectRepository projects = mock(AnalyzedProjectRepository.class);
    private final QueryService query = new QueryService(null, classes, methods, null, annotations,
            null, null, null, projects, null, null, null);
    private final ClassInfo main = clazz(10L, "main", "main-sha");
    private final ClassInfo release = clazz(11L, "release", "release-sha");

    private static ClassInfo clazz(Long id, String branch, String commit) {
        return ClassInfo.builder().id(id).projectId(1L).qualifiedName("Controller")
                .simpleName("Controller").branch(branch).commitId(commit).build();
    }

    private void fixture() {
        when(projects.findAllById(any())).thenReturn(List.of(AnalyzedProjectEntity.builder().id(1L)
                .projectName("api").branch("other-latest-branch").commitId("other-latest-sha").build()));
        when(methods.findByClassInfoIdIn(anyList())).thenAnswer(call -> {
            List<?> ids = call.getArgument(0);
            return List.of(main, release).stream().filter(c -> ids.contains(c.getId())).map(c ->
                    MethodInfo.builder().id(c.getId()).classInfo(c).methodName("get")
                            .methodSignature("Controller.get()").build()).toList();
        });
        when(annotations.findByAnnotationNameInAndTargetTypeAndTargetIdIn(anyList(), eq("METHOD"), anyList()))
                .thenAnswer(call -> {
                    List<Long> ids = call.getArgument(2);
                    return ids.stream().map(id -> AnnotationInfo.builder().targetId(id).annotationName("GetMapping")
                            .annotationParams("{\"path\":\"/health\"}").build()).toList();
                });
    }

    @Test void retainsSameApiAcrossBranchesAndUsesSnapshotVersion() {
        fixture();
        when(classes.findByIsHeadTrue()).thenReturn(List.of(main, release));
        var endpoints = query.getEndpoints(null, null, null);
        assertEquals(2, endpoints.size());
        assertEquals(Set.of("main/main-sha", "release/release-sha"), endpoints.stream()
                .map(e -> e.branch() + "/" + e.commitId()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(endpoints.stream().allMatch(e -> e.projectId().equals(1L)));
    }

    @Test void appliesBranchFilterToGlobalHeadCatalogInDatabase() {
        fixture();
        when(classes.findByBranchAndIsHeadTrue("release")).thenReturn(List.of(release));
        var endpoints = query.getEndpoints(null, "release", null);
        assertEquals(1, endpoints.size());
        assertEquals("release", endpoints.getFirst().branch());
        verify(classes, never()).findByIsHeadTrue();
    }

    @Test void exactVersionUsesBothBranchAndCommitEvenWithoutModuleFilter() {
        fixture();
        when(classes.findByBranchAndCommitId("release", "release-sha")).thenReturn(List.of(release));
        var endpoints = query.getEndpoints(null, "release", "release-sha");
        assertEquals(1, endpoints.size());
        assertEquals("release-sha", endpoints.getFirst().commitId());
        verify(classes, never()).findByCommitId(anyString());
    }
}
