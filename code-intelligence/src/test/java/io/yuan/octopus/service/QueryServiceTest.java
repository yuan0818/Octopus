package io.yuan.octopus.service;

import io.yuan.octopus.dto.response.EndpointDTO;
import io.yuan.octopus.dto.response.EntryPointDTO;
import io.yuan.octopus.entity.AnnotationInfo;
import io.yuan.octopus.entity.ClassInfo;
import io.yuan.octopus.entity.AnalyzedProjectEntity;
import io.yuan.octopus.entity.MethodInfo;
import io.yuan.octopus.repository.AnnotationInfoRepository;
import io.yuan.octopus.repository.ClassDependencyRepository;
import io.yuan.octopus.repository.ClassInfoRepository;
import io.yuan.octopus.repository.ClassInterfaceRelationRepository;
import io.yuan.octopus.repository.FieldInfoRepository;
import io.yuan.octopus.repository.InheritanceRelationRepository;
import io.yuan.octopus.repository.AnalyzedProjectRepository;
import io.yuan.octopus.repository.MethodInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.Node;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock
    private Driver neo4jDriver;
    @Mock
    private ClassInfoRepository classInfoRepository;
    @Mock
    private MethodInfoRepository methodInfoRepository;
    @Mock
    private FieldInfoRepository fieldInfoRepository;
    @Mock
    private AnnotationInfoRepository annotationInfoRepository;
    @Mock
    private InheritanceRelationRepository inheritanceRelationRepository;
    @Mock
    private ClassDependencyRepository classDependencyRepository;
    @Mock
    private ClassInterfaceRelationRepository classInterfaceRelationRepository;
    @Mock
    private AnalyzedProjectRepository javaProjectRepository;
    @Mock
    private GitService gitService;
    @Mock
    private RepoConfigService repoConfigService;
    @Mock
    private Session session;
    @Mock
    private Result result;

    private QueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new QueryService(
                neo4jDriver,
                classInfoRepository,
                methodInfoRepository,
                fieldInfoRepository,
                annotationInfoRepository,
                inheritanceRelationRepository,
                classDependencyRepository,
                classInterfaceRelationRepository,
                javaProjectRepository,
                gitService,
                repoConfigService, org.mockito.Mockito.mock(RepositoryScopeService.class)
        );
    }

    @Test
    void shouldListEntryPointsWithHttpFirstOrdering() {
        ClassInfo controllerClass = ClassInfo.builder()
                .id(1L)
                .projectId(100L)
                .qualifiedName("com.example.OrderController")
                .simpleName("OrderController")
                .isHead(true)
                .build();
        ClassInfo schedulerClass = ClassInfo.builder()
                .id(2L)
                .projectId(100L)
                .qualifiedName("com.example.CleanupJob")
                .simpleName("CleanupJob")
                .isHead(true)
                .build();
        ClassInfo listenerClass = ClassInfo.builder()
                .id(3L)
                .projectId(100L)
                .qualifiedName("com.example.ReportConsumer")
                .simpleName("ReportConsumer")
                .isHead(true)
                .build();
        MethodInfo endpointMethod = MethodInfo.builder()
                .id(11L)
                .classInfo(controllerClass)
                .methodName("getOrder")
                .methodSignature("com.example.OrderController.getOrder(java.lang.String):Order")
                .returnType("Order")
                .build();
        MethodInfo scheduledMethod = MethodInfo.builder()
                .id(22L)
                .classInfo(schedulerClass)
                .methodName("cleanup")
                .methodSignature("com.example.CleanupJob.cleanup():void")
                .returnType("void")
                .build();
        MethodInfo listenerMethod = MethodInfo.builder()
                .id(33L)
                .classInfo(listenerClass)
                .methodName("consume")
                .methodSignature("com.example.ReportConsumer.consume():void")
                .returnType("void")
                .build();
        AnalyzedProjectEntity project = AnalyzedProjectEntity.builder()
                .id(100L)
                .projectName("order-service")
                .branch("main")
                .commitId("abc123")
                .build();

        when(classInfoRepository.findByIsHeadTrue()).thenReturn(List.of(controllerClass, schedulerClass, listenerClass));
        when(methodInfoRepository.findByClassInfoIdIn(anyList())).thenReturn(List.of(endpointMethod, scheduledMethod, listenerMethod));
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("RequestMapping")), eq("CLASS"), anyList()))
                .thenReturn(List.of(AnnotationInfo.builder()
                        .annotationName("RequestMapping")
                        .annotationParams("{\"value\":\"\\\"/api/orders\\\"\"}")
                        .targetType("CLASS")
                        .targetId(1L)
                        .build()));
        when(javaProjectRepository.findAllById(any())).thenReturn(List.of(project));
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping", "HttpRoute")),
                eq("METHOD"),
                anyList()))
                .thenReturn(List.of(AnnotationInfo.builder()
                        .annotationName("GetMapping")
                        .annotationParams("{\"value\":\"\\\"/{id}\\\"\"}")
                        .targetType("METHOD")
                        .targetId(11L)
                        .build()));
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("EventListener", "KafkaListener", "RabbitListener", "JmsListener", "SqsListener", "RocketMQMessageListener")),
                eq("METHOD"),
                anyList()))
                .thenReturn(List.of(AnnotationInfo.builder()
                        .annotationName("KafkaListener")
                        .annotationParams("{\"topics\":\"{ \\\"#{reportConfig.getCustomObjectReportTopic()}\\\" }\"}")
                        .targetType("METHOD")
                        .targetId(33L)
                        .build()));
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("Scheduled")),
                eq("METHOD"),
                anyList()))
                .thenReturn(List.of(AnnotationInfo.builder()
                        .annotationName("Scheduled")
                        .annotationParams("{\"cron\":\"\\\"0 0 * * * ?\\\"\"}")
                        .targetType("METHOD")
                        .targetId(22L)
                        .build()));

        // getEntryPoints 走 MySQL 路径，不涉及 Neo4j，无需 stub neo4jDriver

        List<EntryPointDTO> entryPoints = queryService.getEntryPoints(null, null, null, null);

        assertEquals(3, entryPoints.size());
        assertEquals("HTTP_API", entryPoints.get(0).entryType());
        assertEquals("GET", entryPoints.get(0).httpMethod());
        assertEquals("/api/orders/{id}", entryPoints.get(0).path());
        assertEquals("MESSAGE_LISTENER", entryPoints.get(1).entryType());
        assertEquals("#{reportConfig.getCustomObjectReportTopic()}", entryPoints.get(1).triggerLabel());
        assertEquals("SCHEDULED", entryPoints.get(2).entryType());
        assertEquals("0 0 * * * ?", entryPoints.get(2).triggerLabel());
    }

    @Test
    void shouldKeepEndpointsCompatibilityForHttpOnlyListing() {
        ClassInfo controllerClass = ClassInfo.builder()
                .id(1L)
                .projectId(100L)
                .qualifiedName("com.example.OrderController")
                .simpleName("OrderController")
                .isHead(true)
                .build();
        MethodInfo endpointMethod = MethodInfo.builder()
                .id(11L)
                .classInfo(controllerClass)
                .methodName("getOrder")
                .methodSignature("com.example.OrderController.getOrder(java.lang.String):Order")
                .returnType("Order")
                .build();

        when(classInfoRepository.findByIsHeadTrue()).thenReturn(List.of(controllerClass));
        when(methodInfoRepository.findByClassInfoIdIn(anyList())).thenReturn(List.of(endpointMethod));
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("RequestMapping")), eq("CLASS"), anyList()))
                .thenReturn(List.of(AnnotationInfo.builder()
                        .annotationName("RequestMapping")
                        .annotationParams("{\"value\":\"\\\"/api/orders\\\"\"}")
                        .targetType("CLASS")
                        .targetId(1L)
                        .build()));
        when(javaProjectRepository.findAllById(any())).thenReturn(List.of());
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping", "HttpRoute")),
                eq("METHOD"),
                anyList()))
                .thenReturn(List.of(AnnotationInfo.builder()
                        .annotationName("GetMapping")
                        .annotationParams("{\"value\":\"\\\"/{id}\\\"\"}")
                        .targetType("METHOD")
                        .targetId(11L)
                        .build()));

        List<EndpointDTO> endpoints = queryService.getEndpoints(null, null, null);

        assertEquals(1, endpoints.size());
        assertEquals("GET", endpoints.get(0).httpMethod());
        assertEquals("/api/orders/{id}", endpoints.get(0).path());
        assertEquals("OrderController", endpoints.get(0).controllerSimpleName());
    }

    @Test
    void shouldIncludeRootMethodsWhenRequested() {
        when(classInfoRepository.findByIsHeadTrue()).thenReturn(List.of());
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);

        Node rootNode = mockNode(Map.of(
                "signature", "com.example.BatchRunner.run():void",
                "qualifiedClassName", "com.example.BatchRunner",
                "methodName", "run",
                "projectId", 7L,
                "branch", "main",
                "commitId", "def456"
        ));
        Value rootValue = org.mockito.Mockito.mock(Value.class);
        when(rootValue.asNode()).thenReturn(rootNode);
        Record record = org.mockito.Mockito.mock(Record.class);
        when(record.get("entry")).thenReturn(rootValue);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(record);

        List<EntryPointDTO> entryPoints = queryService.getEntryPoints(null, "ROOT_METHOD", null, null);

        assertEquals(1, entryPoints.size());
        assertEquals("ROOT_METHOD", entryPoints.get(0).entryType());
        assertEquals("run", entryPoints.get(0).methodName());
        assertNull(entryPoints.get(0).httpMethod());
    }

    private Node mockNode(Map<String, Object> properties) {
        Node node = org.mockito.Mockito.mock(Node.class);
        when(node.containsKey(anyString())).thenAnswer(invocation -> properties.containsKey(invocation.getArgument(0)));
        when(node.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return properties.containsKey(key) ? Values.value(properties.get(key)) : Values.NULL;
        });
        return node;
    }

    // ── 版本化查询测试 ────────────────────────────────────────────

    @Test
    void shouldGetEndpointsWithBranchAndCommitId() {
        ClassInfo controllerClass = ClassInfo.builder()
                .id(1L).projectId(100L)
                .qualifiedName("com.example.OrderController")
                .simpleName("OrderController")
                .branch("main").commitId("abc123").isHead(false)
                .build();
        MethodInfo endpointMethod = MethodInfo.builder()
                .id(11L).classInfo(controllerClass)
                .methodName("getOrder")
                .methodSignature("com.example.OrderController.getOrder(java.lang.String):Order")
                .returnType("Order")
                .commitId("abc123").isHead(false)
                .build();
        AnalyzedProjectEntity project = AnalyzedProjectEntity.builder()
                .id(100L).projectName("order-service")
                .branch("main").commitId("abc123")
                .build();

        when(classInfoRepository.findByProjectIdAndBranchAndCommitId(100L, "main", "abc123"))
                .thenReturn(List.of(controllerClass));
        when(methodInfoRepository.findByClassInfoIdIn(anyList())).thenReturn(List.of(endpointMethod));
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("RequestMapping")), eq("CLASS"), anyList()))
                .thenReturn(List.of());
        when(javaProjectRepository.findAllById(any())).thenReturn(List.of(project));
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping", "HttpRoute")),
                eq("METHOD"), anyList()))
                .thenReturn(List.of(AnnotationInfo.builder()
                        .annotationName("GetMapping")
                        .annotationParams("{\"value\":\"\\\"/{id}\\\"\"}")
                        .targetType("METHOD")
                        .targetId(11L)
                        .build()));

        List<EndpointDTO> endpoints = queryService.getEndpoints(100L, "main", "abc123");

        assertEquals(1, endpoints.size());
        assertEquals("GET", endpoints.get(0).httpMethod());
    }

    @Test
    void shouldGetEndpointsWithIsHeadDefault() {
        ClassInfo controllerClass = ClassInfo.builder()
                .id(1L).projectId(100L)
                .qualifiedName("com.example.OrderController")
                .simpleName("OrderController")
                .isHead(true)
                .build();
        MethodInfo endpointMethod = MethodInfo.builder()
                .id(11L).classInfo(controllerClass)
                .methodName("getOrder")
                .methodSignature("com.example.OrderController.getOrder(java.lang.String):Order")
                .returnType("Order")
                .build();

        when(classInfoRepository.findByProjectIdAndIsHeadTrue(100L))
                .thenReturn(List.of(controllerClass));
        when(methodInfoRepository.findByClassInfoIdIn(anyList())).thenReturn(List.of(endpointMethod));
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("RequestMapping")), eq("CLASS"), anyList()))
                .thenReturn(List.of());
        when(javaProjectRepository.findAllById(any())).thenReturn(List.of());
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping", "HttpRoute")),
                eq("METHOD"), anyList()))
                .thenReturn(List.of(AnnotationInfo.builder()
                        .annotationName("GetMapping")
                        .annotationParams("{\"value\":\"\\\"/orders\\\"\"}")
                        .targetType("METHOD")
                        .targetId(11L)
                        .build()));

        // 不传 commitId → 走 isHead 路径
        List<EndpointDTO> endpoints = queryService.getEndpoints(100L, null, null);

        assertEquals(1, endpoints.size());
        assertEquals("/orders", endpoints.get(0).path());
    }

    @Test
    void shouldGetEntryPointsWithCommitIdFilter() {
        ClassInfo controllerClass = ClassInfo.builder()
                .id(1L).projectId(100L)
                .qualifiedName("com.example.OrderController")
                .simpleName("OrderController")
                .commitId("v1").isHead(false)
                .build();
        MethodInfo endpointMethod = MethodInfo.builder()
                .id(11L).classInfo(controllerClass)
                .methodName("getOrder")
                .methodSignature("com.example.OrderController.getOrder(java.lang.String):Order")
                .returnType("Order")
                .build();
        AnalyzedProjectEntity project = AnalyzedProjectEntity.builder()
                .id(100L).projectName("order-service")
                .branch("main").commitId("v1")
                .build();

        when(classInfoRepository.findByProjectIdAndCommitId(100L, "v1"))
                .thenReturn(List.of(controllerClass));
        when(methodInfoRepository.findByClassInfoIdIn(anyList())).thenReturn(List.of(endpointMethod));
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("RequestMapping")), eq("CLASS"), anyList()))
                .thenReturn(List.of());
        when(javaProjectRepository.findAllById(any())).thenReturn(List.of(project));
        when(annotationInfoRepository.findByAnnotationNameInAndTargetTypeAndTargetIdIn(
                eq(List.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping", "HttpRoute")),
                eq("METHOD"), anyList()))
                .thenReturn(List.of(AnnotationInfo.builder()
                        .annotationName("GetMapping")
                        .annotationParams("{\"value\":\"\\\"/{id}\\\"\"}")
                        .targetType("METHOD")
                        .targetId(11L)
                        .build()));

        List<EntryPointDTO> entries = queryService.getEntryPoints(100L, "HTTP_API", null, "v1");

        assertEquals(1, entries.size());
        assertEquals("HTTP_API", entries.get(0).entryType());
    }
}
