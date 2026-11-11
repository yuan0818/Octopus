package io.yuan.octopus.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import java.util.Set;

/** Source context preserves identifiers and HTTP field names, never arbitrary literal values or comments. */
final class ModelSourceSanitizer {
    private static final Set<String> FIELD_ACCESS = Set.of("getFirst", "getHeader", "getParameter", "getClaim", "getQueryParam", "getString", "getInteger", "getBoolean", "containsKey");
    private static final Set<String> BINDINGS = Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestHeader", "RequestParam", "PathVariable", "JsonProperty");
    static String sanitize(Node node) {
        return sanitize(node, false);
    }
    static String sanitizeRepository(Node node) { return sanitize(node, true); }
    private static String sanitize(Node node, boolean repository) {
        if(repository) node.findAll(MethodDeclaration.class).stream().filter(m->m.getNameAsString().equals("main") && m.isStatic()).toList().forEach(Node::remove);
        node.getAllContainedComments().forEach(c -> c.remove()); node.removeComment();
        node.findAll(TextBlockLiteralExpr.class).forEach(s -> s.setValue("[redacted]"));
        node.findAll(StringLiteralExpr.class).forEach(s -> {
            String value = s.asString(); boolean keep = false;
            if (repository && value.matches("\\$\\{[A-Za-z0-9_.-]+(?::[^}]*)?}")) {
                s.setString(value.replaceFirst(":.*}", "}")); return;
            }
            if (repository && Set.of("", "MD5", "SHA-256", "SHA256", "RSA", "AES", "AES/CBC/PKCS5Padding", "AES/ECB/PKCS5Padding", "UTF-8", "utf-8", "bearer ", "Bearer ", "key", "key=", "=", "&", "on", "off").contains(value)) keep = true;
            if (value.length() <= 100 && value.matches("[A-Za-z0-9_./{}*:-]+")) {
                var parent = s.getParentNode().orElse(null);
                if (parent instanceof MethodCallExpr call && !call.getArguments().isEmpty() && call.getArgument(0) == s)
                    keep |= FIELD_ACCESS.contains(call.getNameAsString()) || repository && Set.of("put", "header", "queryParam", "getProperty", "getRequiredProperty", "getenv").contains(call.getNameAsString())
                            || repository && value.startsWith("/") && Set.of("add", "contains", "startsWith", "path").contains(call.getNameAsString())
                            || repository && Set.of("contains", "startsWith", "endsWith", "equals").contains(call.getNameAsString()) && call.getScope().map(scope->scope instanceof NameExpr name && name.getNameAsString().toLowerCase(java.util.Locale.ROOT).contains("path") || scope instanceof MethodCallExpr method && Set.of("getPath","getRawPath").contains(method.getNameAsString())).orElse(false);
                var annotation = s.findAncestor(AnnotationExpr.class).orElse(null);
                if (annotation != null) {
                    String name = annotation.getName().getIdentifier();
                    keep |= BINDINGS.contains(name) && (parent instanceof SingleMemberAnnotationExpr || parent instanceof MemberValuePair pair && Set.of("name", "value", "path").contains(pair.getNameAsString()));
                    keep |= repository && name.equals("ConfigurationProperties");
                    keep |= repository && Set.of("ConditionalOnProperty","NacosPropertySource","NacosConfigurationProperties").contains(name) && parent instanceof MemberValuePair pair && Set.of("name","prefix","dataId","groupId","group").contains(pair.getNameAsString());
                    keep |= repository && Set.of("SpringBootApplication", "ComponentScan").contains(name) && value.matches("[A-Za-z0-9_.*]+") && parent instanceof MemberValuePair pair && Set.of("scanBasePackages", "basePackages").contains(pair.getNameAsString());
                }
            }
            if(repository && value.matches("(?:classpath\\*?:)?[A-Za-z0-9_./-]+\\.(?:properties|yml|yaml)") && s.findAncestor(AnnotationExpr.class).map(a->a.getName().getIdentifier().equals("PropertySource")).orElse(false))keep=true;
            if (repository && value.length() < 300 && value.matches("@annotation\\([A-Za-z0-9_.$]+\\)") && s.findAncestor(AnnotationExpr.class).isPresent()) keep = true;
            if (repository && value.matches("[A-Za-z0-9_]+\\(\\)") && s.findAncestor(AnnotationExpr.class).map(a->Set.of("Before","After","AfterReturning","Around").contains(a.getNameAsString())).orElse(false)) keep = true;
            if (!keep) s.setString("[redacted]");
        });
        return node.toString();
    }
}
