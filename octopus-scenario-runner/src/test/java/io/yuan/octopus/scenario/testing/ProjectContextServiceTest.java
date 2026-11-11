package io.yuan.octopus.scenario.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.yuan.octopus.scenario.testing.ProjectContextService.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.OptimisticLockingFailureException;
import java.util.*;

class ProjectContextServiceTest {
    final Map<String, ProjectContext> rows = new HashMap<>();
    ProjectContextRepository repository;
    RepositoryCatalogClient catalog;
    ProjectContextService service;
    final ContextCredentialCipher cipher = new ContextCredentialCipher("0123456789abcdef0123456789abcdef");
    @BeforeEach void setup() {
        repository = mock(ProjectContextRepository.class); catalog = mock(RepositoryCatalogClient.class);
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repository.saveAndFlush(any())).thenAnswer(i -> { ProjectContext row = i.getArgument(0); row.version = row.version == null ? 0 : row.version + 1; rows.put(row.id, row); return row; });
        service = new ProjectContextService(repository, catalog, cipher);
    }
    Connection connection(String host) { return new Connection("accounts", "账号数据库", "mysql", host, 3306, "accounts", "验证用户状态"); }
    Definition definition(String url, String host) { return new Definition("用户账号业务", "测试数据独立", url, List.of(connection(host))); }
    Resolved save(String key, String module, String env, Long version, Definition definition, Map<String, CredentialUpdate> secrets) {
        return service.save(new Save(key, module, env, version, definition, secrets));
    }
    @Test void presetEnvironmentsKeepCustomConfigurationsWithoutCreatingRows() {
        assertEquals(List.of("test", "staging", "production"), service.environments("repo:1"));
        assertTrue(rows.isEmpty());
        save("repo:1", "", "qa", null, definition("https://qa.example.com", "qa-db"), Map.of());
        when(repository.findByRepositoryKey("repo:1")).thenReturn(new ArrayList<>(rows.values()));
        assertEquals(List.of("test", "staging", "production", "qa"), service.environments("repo:1"));
        assertEquals("https://qa.example.com", service.get("repo:1", "", "qa").effective().baseUrl());
        assertEquals("", service.get("repo:1", "", "production").effective().baseUrl());
        assertEquals(1, rows.size());
    }
    @Test void variablesAreVersionCompatibleInheritedAndIsolatedWithoutEnteringModelValues() {
        var legacy = read("{\"description\":\"\",\"businessRules\":\"\",\"baseUrl\":\"\",\"connections\":[]}", Definition.class);
        assertTrue(legacy.variables().isEmpty());
        var root = new Definition("", "", "", List.of(), List.of(new EnvironmentVariable("tenant", "string", "fixture-tenant-value"), new EnvironmentVariable("retries", "number", 3)));
        save("repo:1", "", "test", null, root, Map.of());
        var own = save("repo:1", "auth", "test", null, new Definition("", "", "", List.of(), List.of(new EnvironmentVariable("tenant", "string", "module-tenant"))), Map.of());
        assertEquals("module-tenant", own.effective().variables().getFirst().value());
        assertEquals(2, own.effective().variables().size());
        assertTrue(service.get("repo:1", "auth", "production").effective().variables().isEmpty());
        assertTrue(service.get("repo:2", "auth", "test").effective().variables().isEmpty());
        String prompt = canonical(service.modelContext("repo:1", "", "test"));
        assertTrue(prompt.contains("tenant")); assertFalse(prompt.contains("fixture-tenant-value"));
        var reverted = save("repo:1", "auth", "test", own.own().version(), empty(), Map.of());
        assertEquals("fixture-tenant-value", reverted.effective().variables().getFirst().value());
    }
    @Test void rejectsAmbiguousOrWronglyTypedVariables() {
        var v = new EnvironmentVariable("tenant", "string", "x");
        assertThrows(IllegalArgumentException.class, () -> validate(new Definition("", "", "", List.of(), List.of(v, v))));
        assertThrows(IllegalArgumentException.class, () -> validate(new Definition("", "", "", List.of(), List.of(new EnvironmentVariable("count", "number", "3")))));
        assertThrows(IllegalArgumentException.class, () -> validate(new Definition("", "", "", List.of(), List.of(new EnvironmentVariable("bad key", "string", "x")))));
    }
    @Test void globalProjectAndModuleResolutionPreservesFalsyValuesAndWholeObjects() {
        service.saveGlobal(new SaveGlobal("test",null,new Definition("","","",List.of(),List.of(
                new EnvironmentVariable("enabled","boolean",true),new EnvironmentVariable("limit","integer",8),
                new EnvironmentVariable("options","object",Map.of("a",1,"b",2)))),Map.of()));
        save("repo:1","","test",null,new Definition("","","",List.of(),List.of(new EnvironmentVariable("limit","integer",0))),Map.of());
        var resolved=save("repo:1","api","test",null,new Definition("","","",List.of(),List.of(
                new EnvironmentVariable("enabled","boolean",false),new EnvironmentVariable("options","object",Map.of("c",3)))),Map.of());
        var values=new HashMap<String,Object>();resolved.effective().variables().forEach(v->values.put(v.key(),v.value()));
        assertEquals(false,values.get("enabled"));assertEquals(0,((Number)values.get("limit")).intValue());
        assertEquals("模块配置",resolved.variableOrigins().get("options"));assertEquals(1,((Map<?,?>)values.get("options")).size());
        assertTrue(service.get("repo:1","api","staging").effective().variables().isEmpty());
        assertThrows(IllegalArgumentException.class,()->save("repo:1","other","test",null,
                new Definition("","","",List.of(),List.of(new EnvironmentVariable("limit","string","zero"))),Map.of()));
    }
    @Test void credentialsAreEncryptedWriteOnlyAndNeverEnterModelContext() {
        var result = save("repo:1", "", "test", null, definition("https://test.example.com", "db.internal"),
                Map.of("accounts", new CredentialUpdate("fixture-user", "fixture-password", false)));
        var row = rows.values().iterator().next();
        assertFalse(row.credentialsEncrypted.contains("fixture-password"));
        assertTrue(cipher.decrypt(row.credentialsEncrypted).contains("fixture-password"));
        assertFalse(canonical(result).contains("fixture-user")); assertFalse(canonical(result).contains("fixture-password"));
        assertTrue(result.own().credentials().get("accounts").hasPassword());
        String prompt = canonical(service.modelContext("repo:1", "", "test"));
        assertFalse(prompt.contains("fixture-user")); assertFalse(prompt.contains("fixture-password"));
        assertFalse(prompt.contains("db.internal")); assertFalse(prompt.contains("https://"));
        assertTrue(prompt.contains("mysql")); assertTrue(prompt.contains("测试数据独立"));
    }
    @Test void omittedCredentialsArePreservedAndExplicitClearRemovesThem() {
        var first = save("repo:1", "", "test", null, definition("", "db"), Map.of("accounts", new CredentialUpdate("user", "password", false)));
        var second = save("repo:1", "", "test", first.own().version(), definition("", "db2"), Map.of());
        assertTrue(second.own().credentials().get("accounts").hasPassword());
        var third = save("repo:1", "", "test", second.own().version(), definition("", "db2"), Map.of("accounts", new CredentialUpdate(null, null, true)));
        assertTrue(third.own().credentials().isEmpty()); assertEquals("", rows.values().iterator().next().credentialsEncrypted);
    }
    @Test void runtimeResolvesOwningConnectionCredentialsAndNeverFallsBackAcrossEnvironments() {
        save("repo:1", "", "test", null, definition("", "root-db"), Map.of("accounts", new CredentialUpdate("root-user", "root-password", false)));
        var source = new io.yuan.octopus.verification.VerificationPlan.Source("repo:1", "auth", "accounts");
        var inherited = service.runtimeConnection(source, "test", "SQL");
        assertEquals("root-db", inherited.host()); assertEquals("root-password", inherited.password());
        assertThrows(IllegalArgumentException.class, () -> service.runtimeConnection(source, "missing-env", "SQL"));
        assertThrows(IllegalArgumentException.class, () -> service.runtimeConnection(source, "test", "REDIS"));
        save("repo:1", "auth", "test", null, definition("", "module-db"), Map.of());
        var overridden = service.runtimeConnection(source, "test", "SQL");
        assertEquals("module-db", overridden.host()); assertEquals("", overridden.password());
        assertFalse(overridden.toString().contains("module-db"));
    }
    @Test void moduleOverridesAndEnvironmentAndRepositoryIsolation() {
        save("repo:1", "", "test", null, definition("https://test.example.com", "root-db"), Map.of());
        var inherited = service.get("repo:1", "auth", "test");
        assertTrue(inherited.inheritedBaseUrl()); assertEquals("root-db", inherited.effective().connections().getFirst().host());
        var own = save("repo:1", "auth", "test", null, new Definition("登录模块", "验证码一次有效", "https://auth.example.com", List.of(connection("module-db"))), Map.of());
        assertEquals("https://auth.example.com", own.effective().baseUrl());
        assertEquals(1, own.effective().connections().size()); assertEquals("module-db", own.effective().connections().getFirst().host());
        assertEquals("模块配置", own.connectionOrigins().get("accounts")); assertTrue(own.effective().description().contains("用户账号业务"));
        assertEquals("", service.get("repo:1", "auth", "staging").effective().baseUrl());
        assertEquals("", service.get("repo:2", "auth", "test").effective().baseUrl());
        verify(catalog, atLeastOnce()).validate("repo:1", "auth");
    }
    @Test void staleUpdatesCannotReplaceConfiguration() {
        save("repo:1", "", "test", null, definition("", "db"), Map.of());
        assertThrows(OptimisticLockingFailureException.class, () -> save("repo:1", "", "test", null, definition("", "db2"), Map.of()));
        assertEquals("db", service.get("repo:1", "", "test").effective().connections().getFirst().host());
    }
    @Test void credentialsFailClosedWithoutEncryptionKey() {
        service = new ProjectContextService(repository, catalog, new ContextCredentialCipher(""));
        assertThrows(IllegalStateException.class, () -> save("repo:1", "", "test", null, definition("", "db"), Map.of("accounts", new CredentialUpdate("user", "password", false))));
        verify(repository, never()).saveAndFlush(any());
        assertDoesNotThrow(() -> save("repo:1", "", "test", null, definition("", "db"), Map.of()));
    }
    @Test void connectionDeletionAlsoDeletesItsCredentials() {
        var first = save("repo:1", "", "test", null, definition("", "db"), Map.of("accounts", new CredentialUpdate("user", "password", false)));
        var updated = save("repo:1", "", "test", first.own().version(), empty(), Map.of());
        assertTrue(updated.own().credentials().isEmpty());
    }
    @Test void credentialsInUrlsInvalidPortsAndDuplicateConnectionsAreRejected() {
        for (String url : List.of("https://user:password@example.com", "https://example.com?password=value", "file:///tmp/test"))
            assertThrows(IllegalArgumentException.class, () -> ProjectContextService.validate(definition(url, "db")));
        assertThrows(IllegalArgumentException.class, () -> ProjectContextService.validate(definition("", "user:password@db")));
        assertThrows(IllegalArgumentException.class, () -> ProjectContextService.validate(new Definition("", "", "", List.of(connection("db"), connection("db2")))));
        assertThrows(IllegalArgumentException.class, () -> ProjectContextService.validate(new Definition("", "", "", List.of(new Connection("db", "", "mysql", "db", 99999, "", "")))));
    }
}
