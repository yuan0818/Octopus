package io.yuan.octopus.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.yuan.octopus.entity.*;
import io.yuan.octopus.repository.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class QueryEndpointRoutesTest {
    @Test
    void retainsEveryClassPathMethodPathAndHttpVerbForOneHandler() {
        var classes = mock(ClassInfoRepository.class);
        var methods = mock(MethodInfoRepository.class);
        var annotations = mock(AnnotationInfoRepository.class);
        var projects = mock(AnalyzedProjectRepository.class);
        var query =
                new QueryService(
                        null,
                        classes,
                        methods,
                        null,
                        annotations,
                        null,
                        null,
                        null,
                        projects,
                        null,
                        null,
                        null);
        var clazz =
                ClassInfo.builder()
                        .id(10L)
                        .projectId(1L)
                        .qualifiedName("AuthController")
                        .simpleName("AuthController")
                        .branch("main")
                        .commitId("base")
                        .build();
        var method =
                MethodInfo.builder()
                        .id(20L)
                        .classInfo(clazz)
                        .methodName("login")
                        .methodSignature("AuthController.login()")
                        .build();
        when(classes.findByProjectIdAndBranchAndCommitId(1L, "main", "base"))
                .thenReturn(List.of(clazz));
        when(methods.findByClassInfoIdIn(anyList())).thenReturn(List.of(method));
        when(projects.findAllById(any())).thenReturn(List.of());
        when(annotations.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                        anyList(), eq("CLASS"), anyList()))
                .thenReturn(
                        List.of(
                                AnnotationInfo.builder()
                                        .targetId(10L)
                                        .annotationName("RequestMapping")
                                        .annotationParams("{\"path\":[\"/v1\",\"/v2\"]}")
                                        .build()));
        when(annotations.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                        anyList(), eq("METHOD"), anyList()))
                .thenReturn(
                        List.of(
                                AnnotationInfo.builder()
                                        .targetId(20L)
                                        .annotationName("RequestMapping")
                                        .annotationParams(
                                                "{\"value\":\"{\\\"/login\\\","
                                                    + " \\\"/signin\\\"}\",\"method\":\"{RequestMethod.POST,"
                                                    + " RequestMethod.PUT}\"}")
                                        .build()));
        var endpoints = query.getEndpoints(1L, "main", "base");
        assertEquals(8, endpoints.size());
        assertEquals(
                Set.of(
                        "POST /v1/login",
                        "POST /v1/signin",
                        "POST /v2/login",
                        "POST /v2/signin",
                        "PUT /v1/login",
                        "PUT /v1/signin",
                        "PUT /v2/login",
                        "PUT /v2/signin"),
                endpoints.stream()
                        .map(e -> e.httpMethod() + " " + e.path())
                        .collect(java.util.stream.Collectors.toSet()));
        verify(classes).findByProjectIdAndBranchAndCommitId(1L, "main", "base");
    }
}
