package io.yuan.octopus.service;
import io.yuan.octopus.entity.ClassInfo;
import io.yuan.octopus.repository.ClassInfoRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RepositoryBackgroundServiceTest {
    @Test void collectsCrossModuleFiltersAndCryptoAtExactAnalyzedVersion() {
        var scopes=mock(RepositoryScopeService.class);var classes=mock(ClassInfoRepository.class);var source=mock(GitMethodSourceClient.class);
        String commit="a".repeat(40);var gateway=new RepositoryScopeService.Module(1L,"gateway","gateway","main",List.of("main"));var common=new RepositoryScopeService.Module(2L,"common","common","main",List.of("main"));
        when(scopes.scopes()).thenReturn(List.of(new RepositoryScopeService.Scope("repo:1","Platform","/repo",List.of(gateway,common))));
        when(scopes.versions("repo:1","main",null)).thenReturn(List.of(new RepositoryScopeService.Version(commit,List.of(1L,2L),List.of())));
        when(classes.findByProjectIdAndBranchAndCommitId(1L,"main",commit)).thenReturn(List.of(metadata(1,"AuthFilter","/repo/gateway/AuthFilter.java"),metadata(1,"UnrelatedController","/repo/gateway/UnrelatedController.java")));
        when(classes.findByProjectIdAndBranchAndCommitId(2L,"main",commit)).thenReturn(List.of(metadata(2,"CryptoAspect","/repo/common/CryptoAspect.java")));
        when(source.fetchBatch(anyLong(),eq(commit),anyList())).thenAnswer(inv->{List<GitMethodSourceClient.SourceItem> items=inv.getArgument(2);return items.stream().map(i->i.filePath().endsWith(".java")?"class Fixture { @Value(\"${key:private-fixture}\") String key; void check() { request.getHeader(\"sign\"); }}":null).toList();});
        var service=new RepositoryBackgroundService(scopes,classes,source);var result=service.collect("repo:1","main",commit,"test");
        assertEquals(2,result.sources().size());assertTrue(result.sources().stream().anyMatch(s->s.filePath().equals("common/CryptoAspect.java")));
        assertTrue(result.sources().stream().allMatch(s->s.commitId().equals(commit)&&s.text().contains("sign")&&!s.text().contains("private-fixture")));
        assertThrows(IllegalArgumentException.class,()->service.collect("repo:1","main","b".repeat(40),"test"));
        verify(source,never()).fetchBatch(anyLong(),eq("b".repeat(40)),anyList());
    }
    private ClassInfo metadata(long id,String name,String file){var c=new ClassInfo();c.setProjectId(id);c.setSimpleName(name);c.setSourceFilePath(file);return c;}
    @Test void allowsOnlyRoutingAndDependencyStructureFromResourceFiles() {
        String yaml="""
            spring:
              datasource:
                password: private-fixture
              cloud:
                gateway:
                  routes:
                    - uri: lb://auth-server
                      predicates: ["Path=/auth/**"]
                      filters: ["StripPrefix=1", "AddRequestHeader=key,private-fixture"]
                    - uri: https://user:private-fixture@example.invalid
                      predicates: ["Path=/other/**"]
            """;
        String routes=RepositoryBackgroundService.routes(yaml);assertTrue(routes.contains("auth-server"));var first=com.google.gson.JsonParser.parseString(routes).getAsJsonArray().get(0).getAsJsonObject();assertEquals("Path=/auth/**",first.getAsJsonArray("predicates").get(0).getAsString());assertEquals("StripPrefix=1",first.getAsJsonArray("filters").get(0).getAsString());assertFalse(routes.contains("private-fixture"));assertFalse(routes.contains("example.invalid"));
        String pom=RepositoryBackgroundService.dependencies("<project><artifactId>gateway</artifactId><dependencies><dependency><artifactId>component-gateway</artifactId></dependency></dependencies><properties><password>private-fixture</password></properties></project>");
        assertTrue(pom.contains("component-gateway"));assertFalse(pom.contains("private-fixture"));
    }
    @Test void repositorySanitizerKeepsProtocolRulesButRemovesConfigurationDefaults() {
        String safe=ModelSourceSanitizer.sanitizeRepository(com.github.javaparser.StaticJavaParser.parse("""
            class CryptoAspect {
                @Value("${token.secret:private-fixture}") String key;
                String hardcoded="private-fixture";
                @Pointcut("@annotation(com.example.Decrypt)") void decrypt() {}
                @Before("decrypt()") void before() {
                    request.getHeader("sign"); map.put("requestid", requestId);
                    digest("SHA256"); builder.append("key=").append("private-fixture");
                    paths.add("/auth/login");
                    if (path.contains("api-docs") || path.contains("doc")) return;
                }
            }
            """));
        for(String expected:List.of("${token.secret}","sign","requestid","SHA256","key=","/auth/login","@annotation(com.example.Decrypt)","decrypt()","api-docs","\"doc\""))assertTrue(safe.contains(expected),expected);
        assertFalse(safe.contains("private-fixture"));
    }
}
