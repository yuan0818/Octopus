package io.yuan.octopus.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.*;
import com.google.gson.Gson;
import io.yuan.octopus.common.SignatureNormalizer;
import io.yuan.octopus.entity.*;
import io.yuan.octopus.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Reads a selected endpoint's contract from its exact analysed snapshot; never reads workspace HEAD. */
@Service
public class EndpointContractService {
    public record Port(String location, String name, String type, boolean required, String prefix) {}
    public record Contract(long projectId, String branch, String commitId, String signature, String httpMethod, String path,
                           Map<String, Port> inputs, Map<String, Object> requestSchema, Map<String, Object> responseSchema,
                           List<String> warnings) {}
    public record SourceEvidence(String id, long projectId, String commitId, String filePath, int lineStart, int lineEnd,
                                 String kind, String text, boolean truncated) {}
    public record SourceContext(Contract contract, List<SourceEvidence> sources, List<String> warnings) {}
    private final ClassInfoRepository classes;
    private final MethodInfoRepository methods;
    private final AnalyzedProjectRepository projects;
    private final GitMethodSourceClient source;
    private final QueryService query;
    public EndpointContractService(ClassInfoRepository classes, MethodInfoRepository methods, AnalyzedProjectRepository projects,
                                   GitMethodSourceClient source, QueryService query) {
        this.classes = classes; this.methods = methods; this.projects = projects; this.source = source; this.query = query;
    }

    @Transactional(readOnly = true)
    public Contract contract(long projectId, String branch, String commitId, String signature, String httpMethod, String path) {
        return readSnapshot(projectId, branch, commitId, signature, httpMethod, path, false).contract();
    }
    @Transactional(readOnly = true)
    public SourceContext sourceContext(long projectId, String branch, String commitId, String signature, String httpMethod, String path) {
        return readSnapshot(projectId, branch, commitId, signature, httpMethod, path, true);
    }
    private SourceContext readSnapshot(long projectId, String branch, String commitId, String signature, String httpMethod, String path, boolean includeSource) {
        if (projectId <= 0 || branch == null || branch.isBlank() || commitId == null || !commitId.matches("[a-fA-F0-9]{40}") || signature == null)
            throw new IllegalArgumentException("读取契约需要项目、分支、完整提交与方法签名");
        String canonical = SignatureNormalizer.canonical(signature);
        boolean endpoint = query.getEndpoints(projectId, branch, commitId).stream().anyMatch(e ->
                canonical.equals(SignatureNormalizer.canonical(e.signature())) && Objects.equals(path, e.path())
                        && (Objects.equals(httpMethod, e.httpMethod()) || "*".equals(e.httpMethod())));
        if (!endpoint) throw new IllegalArgumentException("所选版本中不存在该 API，不能用其他版本代替");
        var project = projects.findById(projectId).orElseThrow();
        Set<Long> members = new HashSet<>();
        projects.findAll().stream().filter(p -> RepositoryScopeService.key(project).equals(RepositoryScopeService.key(p)))
                .forEach(p -> members.add(p.getId()));
        List<ClassInfo> snapshot = new ArrayList<>();
        members.forEach(id -> snapshot.addAll(classes.findByProjectIdAndBranchAndCommitId(id, branch, commitId)));
        var owner = snapshot.stream().filter(c -> c.getProjectId() == projectId && canonical.startsWith(c.getQualifiedName() + "."))
                .max(Comparator.comparingInt(c -> c.getQualifiedName().length())).orElseThrow();
        var method = methods.findByClassInfoId(owner.getId()).stream()
                .filter(m -> canonical.equals(SignatureNormalizer.canonical(m.getMethodSignature()))).findFirst().orElseThrow();
        var reader = new Reader(snapshot, commitId, includeSource);
        var contract = reader.read(owner, method, httpMethod, path, branch);
        var warnings = new ArrayList<>(contract.warnings());
        if (includeSource) warnings.add("源码范围为当前入口、可解析的直接返回方法和 DTO；未自动覆盖网关链路、动态配置或全部下游调用");
        warnings.addAll(reader.sourceWarnings);
        return new SourceContext(contract, List.copyOf(reader.evidence.values()), warnings);
    }

    final class Reader {
        private final List<ClassInfo> snapshot;
        private final String commit;
        private final JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        private final Map<String, TypeDeclaration<?>> declarations = new HashMap<>();
        private final Set<String> loaded = new HashSet<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private int fields;
        private final boolean includeSource;
        private final Map<String, ClassInfo> origins = new HashMap<>();
        private final Map<String, SourceEvidence> evidence = new LinkedHashMap<>();
        private final Set<String> sourceWarnings = new LinkedHashSet<>();
        private int sourceChars;
        Reader(List<ClassInfo> snapshot, String commit) { this(snapshot, commit, false); }
        Reader(List<ClassInfo> snapshot, String commit, boolean includeSource) { this.snapshot = snapshot; this.commit = commit; this.includeSource = includeSource; }

        Contract read(ClassInfo owner, MethodInfo metadata, String httpMethod, String path, String branch) {
            var declaration = declaration(owner.getQualifiedName());
            MethodDeclaration method = null;
            if (declaration != null) {
                List<String> types = new Gson().fromJson(metadata.getParameterTypes(), new com.google.gson.reflect.TypeToken<List<String>>() {}.getType());
                var candidates = declaration.getMethodsByName(metadata.getMethodName()).stream()
                        .filter(m -> m.getParameters().size() == types.size())
                        .filter(m -> { for (int i = 0; i < types.size(); i++) if (!sameType(qualified(m.getParameter(i).getType(), declaration, Map.of()), types.get(i))) return false; return true; }).toList();
                if (candidates.size() == 1) method = candidates.getFirst();
            }
            // The stored method source belongs to this exact class/commit, but cannot supply missing DTO imports.
            if (method == null && metadata.getSourceCode() != null) {
                var parsed = parser.parseBodyDeclaration(metadata.getSourceCode()).getResult();
                if (parsed.isPresent() && parsed.get().isMethodDeclaration()) method = parsed.get().asMethodDeclaration();
                warnings.add("方法声明使用该提交的分析快照；无法从 Git 恢复的 DTO 不推测字段");
            }
            Map<String, Port> inputs = new LinkedHashMap<>();
            Map<String, Object> request = new LinkedHashMap<>();
            Map<String, Object> parameters = new LinkedHashMap<>();
            Map<String, Object> response = new LinkedHashMap<>();
            if (method == null) warnings.add("无法读取该方法的参数声明");
            else {
                capture(method, declaration, "ENTRY_METHOD");
                for (var parameter : method.getParameters()) {
                    String type = qualified(parameter.getType(), declaration, Map.of());
                    AnnotationExpr binding = annotation(parameter, "RequestBody", "RequestParam", "RequestHeader", "PathVariable");
                    if (binding == null) {
                        if (!type.matches(".*(HttpServletRequest|HttpServletResponse|Principal|BindingResult|Model|ModelMap)$"))
                            warnings.add("参数 " + parameter.getNameAsString() + " 未声明 HTTP 绑定位置，需补充确认");
                        continue;
                    }
                    String location = switch (simple(binding.getNameAsString())) { case "RequestBody" -> "body"; case "RequestHeader" -> "header"; case "PathVariable" -> "path"; default -> "query"; };
                    String name = stringValue(binding, "name", stringValue(binding, "value", parameter.getNameAsString()));
                    var schema = schema(type, declaration, new HashSet<>(), 0, true);
                    boolean required = "path".equals(location) || !"false".equals(value(binding, "required")) && value(binding, "defaultValue") == null;
                    if (location.equals("body")) {
                        request.putAll(schema);
                        // Field ports allow independent context bindings without inventing JSON templates.
                        if (schema.get("properties") instanceof Map<?, ?> properties && !properties.isEmpty()) {
                            for (var property : properties.entrySet()) {
                                String propertyName = String.valueOf(property.getKey());
                                if (property.getValue() instanceof Map<?, ?> p && p.get("type") instanceof String t) {
                                    var port = new Port("body", propertyName, t, required && required(schema, propertyName), null);
                                    String key = portKey("body", propertyName); inputs.put(key, port); parameters.put(key, property.getValue());
                                }
                            }
                        } else if (schema.get("type") instanceof String t) {
                            inputs.put("body", new Port("body", "$", t, required, null)); parameters.put("body", schema);
                        }
                    } else if (schema.get("type") instanceof String t && !Set.of("object", "array").contains(t)) {
                        String key = portKey(location, name); inputs.put(key, new Port(location, name, t, required, null)); parameters.put(key, schema);
                    } else warnings.add("参数 " + name + " 需要额外的结构化参数序列化规则");
                }
                String responseType = qualified(method.getType(), declaration, Map.of());
                if (!responseType.contains("<") && declaration != null) responseType = delegatedResponse(method, declaration, responseType);
                if (responseType.startsWith("org.springframework.http.ResponseEntity<")) responseType = responseType.substring(responseType.indexOf('<') + 1, responseType.length() - 1);
                Map<String, Object> body = schema(responseType, declaration, new HashSet<>(), 0, false);
                var status = annotation(method, "ResponseStatus");
                String code = status == null ? "200" : statusCode(value(status, "code") != null ? value(status, "code") : value(status, "value"));
                if (code != null) response.put(code, body); else warnings.add("响应状态码无法静态解析");
                if (annotation(method, "Decrypt", "Encrypt") != null || declaration != null && annotation(declaration, "Decrypt", "Encrypt") != null)
                    warnings.add("该接口声明了加解密处理；字段结构不等于线上的明密文格式，需要项目背景提供加解密与鉴权规则");
            }
            request.putIfAbsent("type", "object");
            request.put("x-octopus-parameters", parameters);
            if (!warnings.isEmpty()) request.put("x-octopus-contract-warnings", List.copyOf(warnings));
            return new Contract(owner.getProjectId(), branch, commit, metadata.getMethodSignature(), httpMethod, path,
                    inputs, request, response, List.copyOf(warnings));
        }

        private TypeDeclaration<?> declaration(String name) {
            name = name.replace('$', '.');
            if (declarations.containsKey(name)) return declarations.get(name);
            final String target = name;
            var candidates = snapshot.stream().filter(c -> target.equals(c.getQualifiedName().replace('$', '.'))).toList();
            // Root and module analyses can both index the same file in this repository snapshot.
            // Only identical source coordinates may be coalesced; distinct definitions stay ambiguous.
            if (candidates.isEmpty() || candidates.stream().map(ClassInfo::getSourceFilePath).distinct().count() != 1) return null;
            var info = candidates.getFirst();
            String file = info.getProjectId() + ":" + info.getSourceFilePath();
            if (!loaded.add(file) || loaded.size() > 40) return null;
            var texts = source.fetchBatch(info.getProjectId(), commit, List.of(new GitMethodSourceClient.SourceItem(info.getSourceFilePath(), 1, 10000)));
            if (texts.isEmpty() || texts.getFirst() == null || texts.getFirst().length() > 350000) return null;
            parser.parse(texts.getFirst()).getResult().ifPresent(cu -> cu.findAll(TypeDeclaration.class).stream().map(t -> (TypeDeclaration<?>) t).forEach(t ->
                    t.getFullyQualifiedName().ifPresent(q -> { declarations.put(q.replace('$', '.'), t); origins.put(q.replace('$', '.'), info); })));
            return declarations.get(target);
        }
        private boolean known(String name) { return declarations.containsKey(name) || snapshot.stream().anyMatch(c -> name.equals(c.getQualifiedName().replace('$', '.'))); }
        private String resolve(String name, TypeDeclaration<?> owner) {
            if (known(name) || name.startsWith("java.")) return name;
            if (owner != null && owner.findCompilationUnit().isPresent()) {
                var cu = owner.findCompilationUnit().get();
                // Includes nested DTOs and imported nested type declarations.
                var local = cu.findAll(TypeDeclaration.class).stream().map(t -> (TypeDeclaration<?>) t).filter(t -> t.getNameAsString().equals(name) || t.getFullyQualifiedName().orElse("").endsWith("." + name)).toList();
                if (local.size() == 1) return local.getFirst().getFullyQualifiedName().orElse(name);
                var imported = cu.getImports().stream().map(i -> i.isAsterisk() ? i.getNameAsString() + "." + name : i.getNameAsString())
                        .filter(q -> (q.endsWith("." + name) || q.equals(name)) && known(q)).distinct().toList();
                if (imported.size() == 1) return imported.getFirst();
                String nearby = cu.getPackageDeclaration().map(p -> p.getNameAsString() + "." + name).orElse(name);
                if (known(nearby)) return nearby;
                // Preserve explicit external names even when their source was not analysed.
                var external = cu.getImports().stream().filter(i -> !i.isAsterisk() && i.getNameAsString().endsWith("." + name)).toList();
                if (external.size() == 1) return external.getFirst().getNameAsString();
            }
            return name;
        }
        private String qualified(Type type, TypeDeclaration<?> owner, Map<String, String> variables) {
            if (type.isArrayType()) return qualified(type.asArrayType().getComponentType(), owner, variables) + "[]";
            if (!type.isClassOrInterfaceType()) return type.asString();
            var object = type.asClassOrInterfaceType(); String name = object.getNameWithScope();
            if (variables.containsKey(name)) return variables.get(name);
            return resolve(name, owner) + object.getTypeArguments().map(args -> "<" + String.join(",", args.stream().map(t -> qualified(t, owner, variables)).toList()) + ">").orElse("");
        }
        private Map<String, Object> schema(String type, TypeDeclaration<?> owner, Set<String> stack, int depth, boolean input) {
            String primitive = switch (type) {
                case "String", "java.lang.String", "char", "Character", "java.lang.Character", "java.util.UUID", "UUID", "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.OffsetDateTime", "java.util.Date" -> "string";
                case "boolean", "Boolean", "java.lang.Boolean" -> "boolean";
                case "byte", "short", "int", "long", "Byte", "Short", "Integer", "Long", "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.math.BigInteger" -> "integer";
                case "float", "double", "Float", "Double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" -> "number";
                default -> null;
            };
            if (primitive != null) return new LinkedHashMap<>(Map.of("type", primitive));
            if (type.equals("void") || type.equals("Void")) return new LinkedHashMap<>();
            if (depth > 7 || fields > 500 || !stack.add(type)) return unknown(type, "递归或规模限制");
            try {
                Type parsed = parser.parseType(type).getResult().orElse(null);
                if (parsed == null) return unknown(type, "未解析类型");
                if (parsed.isArrayType()) return new LinkedHashMap<>(Map.of("type", "array", "items", schema(parsed.asArrayType().getComponentType().asString(), owner, stack, depth + 1, input)));
                if (!parsed.isClassOrInterfaceType()) return unknown(type, "未绑定泛型");
                var object = parsed.asClassOrInterfaceType(); String raw = object.getNameWithScope();
                var args = object.getTypeArguments().map(a -> a.stream().map(Type::asString).toList()).orElse(List.of());
                if (Set.of("List", "Set", "Collection", "Iterable", "java.util.List", "java.util.Set", "java.util.Collection").contains(raw))
                    return new LinkedHashMap<>(Map.of("type", "array", "items", args.isEmpty() ? unknown(type, "缺少元素类型") : schema(args.getFirst(), owner, stack, depth + 1, input)));
                if (Set.of("Map", "java.util.Map").contains(raw)) return new LinkedHashMap<>(Map.of("type", "object", "additionalProperties", args.size() < 2 ? unknown(type, "缺少值类型") : schema(args.get(1), owner, stack, depth + 1, input)));
                if (Set.of("Optional", "java.util.Optional").contains(raw) && args.size() == 1) return schema(args.getFirst(), owner, stack, depth + 1, input);
                var decl = declaration(raw);
                if (decl == null) return unknown(type, "该分支提交没有可读取的类型声明");
                capture(decl, decl, "DTO");
                if (decl.isEnumDeclaration()) return new LinkedHashMap<>(Map.of("type", "string", "enum", decl.asEnumDeclaration().getEntries().stream().map(e -> e.getNameAsString()).toList()));
                Map<String, String> variables = new HashMap<>();
                var typeParams = decl instanceof ClassOrInterfaceDeclaration c ? c.getTypeParameters() : decl instanceof RecordDeclaration r ? r.getTypeParameters() : new com.github.javaparser.ast.NodeList<TypeParameter>();
                for (int i = 0; i < Math.min(typeParams.size(), args.size()); i++) variables.put(typeParams.get(i).getNameAsString(), args.get(i));
                Map<String, Object> properties = new LinkedHashMap<>(); List<String> required = new ArrayList<>();
                if (decl instanceof ClassOrInterfaceDeclaration c) for (var parent : c.getExtendedTypes()) {
                    var inherited = schema(qualified(parent, decl, variables), decl, stack, depth + 1, input);
                    if (inherited.get("properties") instanceof Map<?, ?> p) p.forEach((k, v) -> properties.put(String.valueOf(k), v));
                    if (inherited.get("required") instanceof List<?> r) r.forEach(k -> required.add(String.valueOf(k)));
                }
                for (var field : decl.getFields()) {
                    if (field.isStatic() || field.isTransient() || ignored(field, input)) continue;
                    for (var variable : field.getVariables()) {
                        fields++; String name = jsonName(field, variable.getNameAsString());
                        var property = schema(qualified(variable.getType(), decl, variables), decl, stack, depth + 1, input);
                        description(field, property); properties.put(name, property);
                        if (annotation(field, "NotNull", "NotBlank", "NotEmpty") != null) required.add(name);
                    }
                }
                if (decl instanceof RecordDeclaration record) for (var p : record.getParameters()) {
                    if (ignored(p, input)) continue; fields++;
                    properties.put(jsonName(p, p.getNameAsString()), schema(qualified(p.getType(), decl, variables), decl, stack, depth + 1, input));
                }
                Map<String, Object> result = new LinkedHashMap<>(); result.put("type", "object"); result.put("properties", properties);
                if (!required.isEmpty()) result.put("required", required.stream().distinct().toList());
                result.put("x-java-type", type); return result;
            } finally { stack.remove(type); }
        }
        private Map<String, Object> unknown(String type, String reason) {
            warnings.add(type + "：" + reason); return new LinkedHashMap<>(Map.of("x-java-type", type, "x-octopus-unresolved", true));
        }
        /** Only direct return calls with one unambiguous declared overload may refine a raw wrapper. */
        private String delegatedResponse(MethodDeclaration method, TypeDeclaration<?> owner, String fallback) {
            var returns = method.findAll(ReturnStmt.class).stream()
                    .filter(r -> r.findAncestor(MethodDeclaration.class).orElse(null) == method && r.findAncestor(LambdaExpr.class).isEmpty()).toList();
            boolean rawWrapperBranch = false;
            Set<String> result = new LinkedHashSet<>();
            for (var ret : returns) {
                if (ret.getExpression().isPresent() && ret.getExpression().get().isObjectCreationExpr()
                        && qualified(ret.getExpression().get().asObjectCreationExpr().getType(), owner, Map.of()).equals(fallback)) {
                    rawWrapperBranch = true; continue;
                }
                if (ret.getExpression().isEmpty() || !ret.getExpression().get().isMethodCallExpr()) return fallback;
                var call = ret.getExpression().get().asMethodCallExpr();
                if (call.getScope().isEmpty() || !call.getScope().get().isNameExpr()) return fallback;
                String fieldName = call.getScope().get().asNameExpr().getNameAsString();
                var field = owner.getFields().stream().flatMap(f -> f.getVariables().stream()).filter(v -> v.getNameAsString().equals(fieldName)).findFirst();
                if (field.isEmpty()) return fallback;
                var target = declaration(qualified(field.get().getType(), owner, Map.of())); if (target == null) return fallback;
                var matches = target.getMethodsByName(call.getNameAsString()).stream().filter(m -> m.getParameters().size() == call.getArguments().size()).filter(m -> {
                    for (int i = 0; i < call.getArguments().size(); i++) {
                        var arg = call.getArgument(i); if (!arg.isNameExpr()) continue;
                        var parameter = method.getParameters().stream().filter(p -> p.getNameAsString().equals(arg.asNameExpr().getNameAsString())).findFirst();
                        if (parameter.isPresent() && !sameType(qualified(parameter.get().getType(), owner, Map.of()), qualified(m.getParameter(i).getType(), target, Map.of()))) return false;
                    } return true;
                }).toList();
                if (matches.size() != 1) return fallback;
                capture(matches.getFirst(), target, "DIRECT_RETURN_METHOD");
                String type = qualified(matches.getFirst().getType(), target, Map.of());
                if (!type.startsWith(fallback + "<")) return fallback;
                result.add(type);
            }
            if (result.size() == 1 && rawWrapperBranch)
                warnings.add("响应包含未指定泛型的包装返回分支；已展开服务方法声明的响应字段，其他分支可能不包含这些字段");
            return result.size() == 1 ? result.iterator().next() : fallback;
        }
        private void capture(Node node, TypeDeclaration<?> owner, String kind) {
            if (!includeSource || owner == null || node.getRange().isEmpty()) return;
            var origin = origins.get(owner.getFullyQualifiedName().orElse("").replace('$', '.'));
            if (origin == null) return;
            int start = node.getRange().get().begin.line, end = node.getRange().get().end.line;
            String id = sourceHash(origin.getSourceFilePath() + ":" + start + ":" + end + ":" + commit).substring(0, 24);
            if (evidence.containsKey(id)) return;
            if (evidence.size() >= 20 || sourceChars >= 48000) { sourceWarnings.add("源码片段达到大小限制，仅提供部分源码"); return; }
            Node excerpt = node.clone();
            if (kind.equals("DTO") && excerpt instanceof TypeDeclaration<?> dto) dto.getMethods().forEach(m -> m.removeBody());
            String text = ModelSourceSanitizer.sanitize(excerpt);
            int limit = Math.min(10000, 48000 - sourceChars);
            boolean truncated = text.length() > limit;
            if (truncated) { text = text.substring(0, limit); sourceWarnings.add("部分源码片段已截断"); }
            sourceChars += text.length();
            evidence.put(id, new SourceEvidence(id, origin.getProjectId(), commit, origin.getSourceFilePath(), start, end, kind, text, truncated));
        }
    }
    private static String sourceHash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean sameType(String a, String b) { return a.replace(" ", "").equals(b.replace(" ", "")) || !a.contains(".") && simple(a).equals(simple(b)) || !b.contains(".") && simple(a).equals(simple(b)); }
    private static String simple(String name) { return name.substring(name.lastIndexOf('.') + 1); }
    private static AnnotationExpr annotation(NodeWithAnnotations<?> node, String... names) { return node.getAnnotations().stream().filter(a -> Arrays.asList(names).contains(simple(a.getNameAsString()))).findFirst().orElse(null); }
    private static String value(AnnotationExpr a, String key) {
        if (a == null) return null;
        if (a.isSingleMemberAnnotationExpr() && key.equals("value")) return a.asSingleMemberAnnotationExpr().getMemberValue().toString();
        if (a.isNormalAnnotationExpr()) return a.asNormalAnnotationExpr().getPairs().stream().filter(p -> p.getNameAsString().equals(key)).map(p -> p.getValue().toString()).findFirst().orElse(null);
        return null;
    }
    private static String stringValue(AnnotationExpr a, String key, String fallback) { String text = value(a, key); if (text == null) return fallback;
        if (text.startsWith("\"") && text.endsWith("\"")) return text.substring(1, text.length() - 1); return fallback; }
    private static String jsonName(NodeWithAnnotations<?> node, String fallback) {
        var jackson = annotation(node, "JsonProperty"); if (jackson != null) return stringValue(jackson, "value", fallback);
        return stringValue(annotation(node, "JSONField"), "name", fallback);
    }
    private static boolean ignored(NodeWithAnnotations<?> node, boolean input) {
        var ignore = annotation(node, "JsonIgnore"); if (ignore != null && !"false".equals(value(ignore, "value"))) return true;
        var property = annotation(node, "JsonProperty"); String access = value(property, "access");
        if (access != null && access.endsWith(input ? "READ_ONLY" : "WRITE_ONLY")) return true;
        return "false".equals(value(annotation(node, "JSONField"), input ? "deserialize" : "serialize"));
    }
    private static void description(NodeWithAnnotations<?> node, Map<String, Object> schema) {
        String description = stringValue(annotation(node, "Schema"), "description", null);
        if (description != null) schema.put("description", description);
    }
    private static boolean required(Map<String, Object> schema, String key) { return schema.get("required") instanceof List<?> list && list.contains(key); }
    private static String portKey(String location, String name) { return location + "." + name.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    private static String statusCode(String value) {
        if (value == null) return null;
        if (value.matches("[1-5][0-9]{2}")) return value;
        return switch (simple(value)) { case "OK" -> "200"; case "CREATED" -> "201"; case "ACCEPTED" -> "202"; case "NO_CONTENT" -> "204"; default -> null; };
    }
}
