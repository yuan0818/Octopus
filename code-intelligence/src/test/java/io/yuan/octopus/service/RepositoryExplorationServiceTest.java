package io.yuan.octopus.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RepositoryExplorationServiceTest {
    @Test void propertiesAreReadableWithoutSelectingAnEnvironment() {
        var scopes=mock(RepositoryScopeService.class);var source=mock(GitMethodSourceClient.class);String commit="b".repeat(40);
        var module=new RepositoryScopeService.Module(1L,"app","app","main",List.of("main"));
        when(scopes.scopes()).thenReturn(List.of(new RepositoryScopeService.Scope("repo","Example","/repo",List.of(module))));
        when(scopes.versions("repo","main",null)).thenReturn(List.of(new RepositoryScopeService.Version(commit,List.of(1L),List.of())));
        when(source.tree(1L,commit)).thenReturn(List.of("app/application.properties"));
        when(source.fetchBatch(eq(1L),eq(commit),anyList())).thenReturn(List.of("spring.config.import=optional:nacos:base.yaml\npassword=private-fixture"));
        var service=new RepositoryExplorationService(scopes,source);var result=service.read(new RepositoryExplorationService.ReadRequest("repo","main",commit,null,List.of("app/application.properties")));
        assertEquals("CONFIG_SCHEMA",result.getFirst().kind());assertTrue(result.getFirst().text().contains("base.yaml"));assertFalse(result.getFirst().text().contains("private-fixture"));
    }
    @Test void readsModelSelectedOrdinaryClassAtPinnedCommitAndBlocksUnknownPaths() {
        var scopes=mock(RepositoryScopeService.class);var source=mock(GitMethodSourceClient.class);String commit="a".repeat(40);
        var module=new RepositoryScopeService.Module(1L,"billing","billing","main",List.of("main"));
        when(scopes.scopes()).thenReturn(List.of(new RepositoryScopeService.Scope("repo","Example","/repo",List.of(module))));
        when(scopes.versions("repo","main",null)).thenReturn(List.of(new RepositoryScopeService.Version(commit,List.of(1L),List.of())));
        when(source.tree(1L,commit)).thenReturn(List.of("billing/OrderPolicy.java","README.md","settings.yml"));
        when(source.fetchBatch(eq(1L),eq(commit),anyList())).thenReturn(List.of("class OrderPolicy { String password=\"private-fixture\"; boolean allowed(){return true;} }"));
        var service=new RepositoryExplorationService(scopes,source);var index=service.inventory("repo","main",commit,"test");
        assertEquals(3,index.files().size());verify(source,never()).fetchBatch(anyLong(),anyString(),anyList());
        var read=service.read(new RepositoryExplorationService.ReadRequest("repo","main",commit,"test",List.of("billing/OrderPolicy.java")));
        assertTrue(read.getFirst().text().contains("allowed"));assertFalse(read.getFirst().text().contains("private-fixture"));
        for(String path:List.of("../secret.java","/repo/billing/OrderPolicy.java","untracked.java"))assertThrows(IllegalArgumentException.class,()->service.read(new RepositoryExplorationService.ReadRequest("repo","main",commit,"test",List.of(path))));
        var unsupported=service.read(new RepositoryExplorationService.ReadRequest("repo","main",commit,"test",List.of("README.md")));
        assertEquals("UNSUPPORTED",unsupported.getFirst().kind());verify(source,times(1)).fetchBatch(anyLong(),anyString(),anyList());
    }
    @Test void configurationNamesAreAvailableWithoutSerializingCredentialValues() {
        var keys=RepositoryExplorationService.configurationKeys("payments:\n  database:\n    password: private-fixture\n    url: jdbc:private-fixture\n");
        assertTrue(keys.contains("payments.database.password"));assertFalse(String.join(" ",keys).contains("private-fixture"));
    }
}
