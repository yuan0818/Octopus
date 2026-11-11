package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.OptimisticLockingFailureException;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@Service
public class ProjectContextService {
    public record Connection(String id, String name, String kind, String host, Integer port, String database, String notes, String security) {
        public Connection(String id, String name, String kind, String host, Integer port, String database, String notes) {
            this(id, name, kind, host, port, database, notes, null);
        }
    }
    public record EnvironmentVariable(String key, String type, Object value) {}
    public record Definition(String description, String businessRules, String baseUrl, List<Connection> connections,
                             List<EnvironmentVariable> variables) {
        public Definition { variables = variables == null ? List.of() : List.copyOf(variables); }
        public Definition(String description, String businessRules, String baseUrl, List<Connection> connections) {
            this(description, businessRules, baseUrl, connections, List.of());
        }
    }
    public record Credential(String username, String password) {
        @Override public String toString() { return "Credential[redacted]"; }
    }
    /** null fields preserve credentials; an explicit clear removes them. No read API returns credentials. */
    public record CredentialUpdate(String username, String password, boolean clear) {
        @Override public String toString() { return "CredentialUpdate[redacted]"; }
    }
    public record Save(String repositoryKey, String modulePath, String environment, Long version,
                       Definition definition, Map<String, CredentialUpdate> credentials) {}
    public record CredentialState(boolean hasUsername, boolean hasPassword) {}
    public record View(String repositoryKey, String modulePath, String environment, Long version,
                       Definition definition, Map<String, CredentialState> credentials, String updatedAt) {}
    public record Resolved(View own, Definition effective, Map<String, String> connectionOrigins,
                           boolean inheritedBaseUrl, boolean encryptionReady, Map<String,String> variableOrigins) {
        public Resolved(View own, Definition effective, Map<String,String> connectionOrigins, boolean inheritedBaseUrl, boolean encryptionReady) {
            this(own,effective,connectionOrigins,inheritedBaseUrl,encryptionReady,Map.of());
        }
    }
    @org.springframework.beans.factory.annotation.Value("${scenario.testing.workspace-id:default}")
    private String workspaceId = "default";
    private final ProjectContextRepository repository;
    private final RepositoryCatalogClient catalog;
    private final ContextCredentialCipher cipher;
    public ProjectContextService(ProjectContextRepository repository, RepositoryCatalogClient catalog, ContextCredentialCipher cipher) {
        this.repository = repository; this.catalog = catalog; this.cipher = cipher;
    }
    public static Definition empty() { return new Definition("", "", "", List.of()); }
    public static String module(String module) { return module == null ? "" : module; }
    private String globalKey() { return "workspace:" + workspaceId; }
    public Resolved global(String environment) {
        environment(environment); return resolve(globalKey(), "", environment);
    }
    public record SaveGlobal(String environment, Long version, Definition definition, Map<String,CredentialUpdate> credentials) {}
    @Transactional
    public Resolved saveGlobal(SaveGlobal request) {
        return saveInternal(new Save(globalKey(),"",request.environment(),request.version(),request.definition(),request.credentials()));
    }
    public static String id(String key, String module, String environment) { return hash(List.of(key, module(module), environment)); }
    public static void environment(String name) { require(name != null && name.matches("[A-Za-z0-9_-]{1,64}"), "环境标识请使用 1–64 位字母、数字、横线或下划线"); }
    public List<String> environments(String key) {
        catalog.scope(key);
        Set<String> names = new LinkedHashSet<>(List.of("test", "staging", "production"));
        repository.findByRepositoryKey(key).forEach(row -> names.add(row.environment));
        repository.findByRepositoryKey(globalKey()).forEach(row -> names.add(row.environment));
        return List.copyOf(names);
    }
    public Resolved get(String key, String module, String environment) {
        catalog.validate(key, module); environment(environment);
        return resolve(key, module(module), environment);
    }
    Resolved resolve(String key, String module, String environment) {
        View own = view(key, module, environment);
        List<View> layers = new ArrayList<>();
        if (!key.equals(globalKey())) layers.add(view(globalKey(), "", environment));
        if (!module.isEmpty()) layers.add(view(key,"",environment));
        layers.add(own);
        Map<String, Connection> connections = new LinkedHashMap<>();
        Map<String, String> origins = new LinkedHashMap<>();
        Map<String, EnvironmentVariable> variables = new LinkedHashMap<>();
        Map<String,String> variableOrigins = new LinkedHashMap<>();
        String url="", description="", rules="";
        for (var layer : layers) {
            String origin=layer.repositoryKey().equals(globalKey()) ? "工作空间全局" : layer.modulePath().isEmpty() ? "项目默认" : "模块配置";
            layer.definition().connections().forEach(c -> {connections.put(c.id(),c);origins.put(c.id(),origin);});
            mergeVariables(variables,layer.definition().variables());
            layer.definition().variables().forEach(v -> variableOrigins.put(v.key(),origin));
            if (!layer.definition().baseUrl().isBlank()) url=layer.definition().baseUrl();
            description=join(description,layer.definition().description());rules=join(rules,layer.definition().businessRules());
        }
        return new Resolved(own,new Definition(description,rules,url,List.copyOf(connections.values()),List.copyOf(variables.values())),
                origins,own.definition().baseUrl().isBlank() && !url.isBlank(),cipher.available(),variableOrigins);
    }
    private static void mergeVariables(Map<String,EnvironmentVariable> target,List<EnvironmentVariable> values) {
        for(var value:values) {
            var previous=target.get(value.key());
            require(previous==null || previous.type().equals(value.type()),"环境变量覆盖不能隐式改变类型："+value.key());
            target.put(value.key(),value);
        }
    }
    private View view(String key, String module, String environment) {
        return repository.findById(id(key, module, environment)).map(row -> {
            Map<String, CredentialState> states = new TreeMap<>();
            // Only presence flags are serialized. Never expose encrypted or decrypted secrets.
            credentials(row).forEach((name, value) -> states.put(name,
                    new CredentialState(!value.username().isEmpty(), !value.password().isEmpty())));
            return new View(key, module, environment, row.version, read(row.definitionJson, Definition.class), states, row.updatedAt.toString());
        }).orElse(new View(key, module, environment, null, empty(), Map.of(), null));
    }
    @Transactional
    public Resolved save(Save request) {
        require(!request.repositoryKey().startsWith("workspace:"),"全局配置请使用工作空间入口");
        return saveInternal(request);
    }
    private Resolved saveInternal(Save request) {
        String module = module(request.modulePath());
        if(!request.repositoryKey().equals(globalKey())) catalog.validate(request.repositoryKey(), module);
        environment(request.environment());
        validate(request.definition());
        Map<String,EnvironmentVariable> types = new LinkedHashMap<>();
        if(!request.repositoryKey().equals(globalKey())) mergeVariables(types,view(globalKey(),"",request.environment()).definition().variables());
        if(!module.isEmpty()) mergeVariables(types,view(request.repositoryKey(),"",request.environment()).definition().variables());
        mergeVariables(types,request.definition().variables());
        String id = id(request.repositoryKey(), module, request.environment());
        var row = repository.findById(id).orElseGet(ProjectContext::new);
        if(row.definitionJson != null) {
            var previous = new LinkedHashMap<String,EnvironmentVariable>();
            mergeVariables(previous,read(row.definitionJson,Definition.class).variables());
            mergeVariables(previous,request.definition().variables());
        }
        if (!Objects.equals(row.version, request.version())) throw new OptimisticLockingFailureException("背景信息已被更新，请刷新后重试");
        Map<String, Credential> secrets = new TreeMap<>(credentials(row));
        var names = request.definition().connections().stream().map(Connection::id).toList();
        secrets.keySet().retainAll(names);
        if (request.credentials() != null) for (var entry : request.credentials().entrySet()) {
            require(names.contains(entry.getKey()) && entry.getValue() != null, "连接凭据必须关联当前配置中的连接");
            var update = entry.getValue();
            if (update.clear()) { secrets.remove(entry.getKey()); continue; }
            var previous = secrets.getOrDefault(entry.getKey(), new Credential("", ""));
            String username = update.username() == null ? previous.username() : update.username();
            String password = update.password() == null ? previous.password() : update.password();
            require(username.length() <= 500 && password.length() <= 8000, "连接凭据过长");
            if (!username.isEmpty() || !password.isEmpty()) secrets.put(entry.getKey(), new Credential(username, password));
            else secrets.remove(entry.getKey());
        }
        row.id = id; row.repositoryKey = request.repositoryKey(); row.modulePath = module; row.environment = request.environment();
        row.definitionJson = canonical(request.definition());
        row.credentialsEncrypted = secrets.isEmpty() ? "" : cipher.encrypt(canonical(secrets));
        row.updatedAt = Instant.now();
        repository.saveAndFlush(row);
        return resolve(row.repositoryKey, module, row.environment);
    }
    private Map<String, Credential> credentials(ProjectContext row) {
        if (row.credentialsEncrypted == null || row.credentialsEncrypted.isBlank()) return Map.of();
        var object = com.google.gson.JsonParser.parseString(cipher.decrypt(row.credentialsEncrypted)).getAsJsonObject();
        Map<String, Credential> result = new TreeMap<>();
        object.entrySet().forEach(e -> result.put(e.getKey(), read(e.getValue(), Credential.class)));
        return result;
    }
    /** Server-only execution path. Resolves exactly the row owning this connection, including inheritance. */
    public io.yuan.octopus.verification.VerificationRuntime.ConnectionSettings runtimeConnection(
            io.yuan.octopus.verification.VerificationPlan.Source source, String environment, String kind) {
        var resolved = get(source.repositoryKey(), source.modulePath(), environment);
        var connection = resolved.effective().connections().stream().filter(c -> c.id().equals(source.connectionId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("所选环境缺少验证连接: " + source.connectionId()));
        boolean own = resolved.own().definition().connections().stream().anyMatch(c -> c.id().equals(source.connectionId()));
        boolean projectOwn = view(source.repositoryKey(),"",environment).definition().connections().stream().anyMatch(c -> c.id().equals(source.connectionId()));
        var credential = repository.findById(id(own || projectOwn ? source.repositoryKey() : globalKey(), own ? source.modulePath() : "", environment))
                .map(this::credentials).orElse(Map.of()).getOrDefault(source.connectionId(), new Credential("", ""));
        var settings = new io.yuan.octopus.verification.VerificationRuntime.ConnectionSettings(connection.kind(), connection.host(),
                connection.port() == null ? switch (connection.kind()) { case "mysql" -> 3306; case "postgresql" -> 5432; case "redis" -> 6379; case "kafka" -> 9092; default -> 0; } : connection.port(),
                connection.database(), connection.security(), credential.username(), credential.password());
        io.yuan.octopus.verification.VerificationRuntime.validateConnection(settings, kind);
        return settings;
    }
    /** Explicit allowlist for model context: no endpoints, hostnames, usernames, passwords or ciphertext. */
    public Map<String, Object> modelContext(String key, String module, String environment) {
        var context = resolve(key, module, environment).effective();
        return Map.of("modulePath", module, "description", context.description(), "businessRules", context.businessRules(),
                "environment", environment, "variables", context.variables().stream().map(v -> Map.of("key", v.key(), "type", v.type())).toList(), "connections", context.connections().stream()
                        .map(c -> Map.of("id", c.id(), "name", c.name(), "kind", c.kind(), "notes", c.notes())).toList());
    }
    static void validate(Definition d) {
        require(d != null && d.connections() != null && d.connections().size() <= 30, "请提供背景和连接信息，最多 30 个连接");
        bounded(d.description(), 12000); bounded(d.businessRules(), 12000); bounded(d.baseUrl(), 2000);
        require(d.variables().size() <= 100, "每个配置最多 100 个环境变量");
        Set<String> variableKeys = new HashSet<>();
        for (var v : d.variables()) {
            require(v != null && v.key() != null && v.key().matches("[A-Za-z_][A-Za-z0-9_.-]{0,119}") && variableKeys.add(v.key()), "环境变量 key 必须唯一，使用字母开头或下划线及字母、数字、点、横线");
            require(v.type() != null && Set.of("string", "integer", "number", "boolean", "object", "array").contains(v.type()) && IrValidator.matches(v.value(), v.type()), "环境变量值与类型不匹配：" + v.key());
            require(canonical(v.value()).length() <= 8000, "环境变量值过长：" + v.key());
        }
        if (!d.baseUrl().isBlank()) {
            URI uri;
            try { uri = URI.create(d.baseUrl()); } catch (RuntimeException e) { throw new IllegalArgumentException("测试地址格式无效"); }
            require(Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null, "测试 URL 需使用 HTTP/HTTPS，认证信息请填入独立凭据字段");
        }
        Set<String> ids = new HashSet<>();
        for (var c : d.connections()) {
            require(c != null && c.id() != null && c.id().matches("[A-Za-z_][A-Za-z0-9_.-]{0,99}") && ids.add(c.id()), "连接标识必须唯一，使用字母、数字、点、横线或下划线");
            bounded(c.name(), 200); bounded(c.host(), 500); bounded(c.database(), 200); bounded(c.notes(), 4000);
            require(c.kind() != null && Set.of("mysql", "postgresql", "mongodb", "redis", "kafka", "rabbitmq", "elasticsearch", "other").contains(c.kind()), "请选择连接类型");
            require(c.host().matches("[A-Za-z0-9_.:\\-\\[\\]]*"), "主机只填写主机名或 IP，用户名和密码请填入独立凭据字段");
            require(c.port() == null || c.port() >= 1 && c.port() <= 65535, "端口需在 1–65535 之间");
            require(c.security() == null || Set.of("plain", "tls", "sasl_ssl_plain", "sasl_ssl_scram256", "sasl_ssl_scram512").contains(c.security()), "请选择受支持的连接认证方式");
        }
    }
    private static void bounded(String value, int max) { require(value != null && value.length() <= max, "背景字段为空或超过长度限制"); }
    private static String join(String parent, String own) { return parent.isBlank() ? own : own.isBlank() ? parent : parent + "\n\n" + own; }
}
