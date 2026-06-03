package io.yuan.octopus.psi;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import org.jetbrains.kotlin.psi.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Extracts Spring AOP aspect-to-target-method bindings from Java and Kotlin.
 * Resolves pointcut expressions (execution, @annotation, within) to actual methods.
 */
public class AOPExtractor {

    private final Project project;
    private List<MethodInfo> allMethodsCache;

    public AOPExtractor(Project project) {
        this.project = project;
    }

    public List<Map<String, Object>> extract() {
        List<Map<String, Object>> bindings = new ArrayList<>();
        List<MethodInfo> allMethods = collectAllMethods();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

            // ── Java aspects ──
            PsiClass[] javaClasses = AllClassesSearch.search(scope, project)
                .findAll()
                .toArray(PsiClass[]::new);

            for (PsiClass psiClass : javaClasses) {
                if (!hasJavaAnnotation(psiClass, "org.aspectj.lang.annotation.Aspect")) continue;

                String aspectClassName = psiClass.getQualifiedName();
                if (aspectClassName == null) continue;

                for (PsiMethod method : psiClass.getMethods()) {
                    Map<String, Object> binding = extractAdviceBindingJava(aspectClassName, method, allMethods);
                    if (binding != null) bindings.add(binding);
                }
            }

            // ── Kotlin aspects ──
            List<KtFile> ktFiles = KotlinPsiHelper.findKotlinFiles(project, scope);
            for (KtFile ktFile : ktFiles) {
                for (KtClassOrObject ktClass : KotlinPsiHelper.getKotlinClasses(ktFile)) {
                    if (!KotlinPsiHelper.hasAnnotation(ktClass, "org.aspectj.lang.annotation.Aspect")) continue;

                    String aspectClassName = KotlinPsiHelper.getKotlinClassFqn(ktClass);

                    for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(ktClass)) {
                        Map<String, Object> binding = extractAdviceBindingKotlin(aspectClassName, func, allMethods);
                        if (binding != null) bindings.add(binding);
                    }
                }
            }
        }

        return bindings;
    }

    // ── Method info cache ─────────────────────────────────────────────

    private record MethodInfo(String signature, String qualifiedClassName, String methodName) {}

    private List<MethodInfo> collectAllMethods() {
        if (allMethodsCache != null) return allMethodsCache;

        List<MethodInfo> methods = new ArrayList<>();
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

            PsiClass[] javaClasses = AllClassesSearch.search(scope, project)
                    .findAll()
                    .toArray(PsiClass[]::new);

            for (PsiClass psiClass : javaClasses) {
                String className = psiClass.getQualifiedName();
                if (className == null) continue;

                for (PsiMethod method : psiClass.getMethods()) {
                    String sig = CallGraphExtractor.buildSignature(psiClass, method);
                    methods.add(new MethodInfo(sig, className, method.getName()));
                }
            }

            List<KtFile> ktFiles = KotlinPsiHelper.findKotlinFiles(project, scope);
            for (KtFile ktFile : ktFiles) {
                for (KtClassOrObject ktClass : KotlinPsiHelper.getKotlinClasses(ktFile)) {
                    String className = KotlinPsiHelper.getKotlinClassFqn(ktClass);

                    for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(ktClass)) {
                        String sig = KotlinPsiHelper.buildKotlinSignature(ktClass, func);
                        methods.add(new MethodInfo(sig, className, func.getName()));
                    }
                }
            }
        }

        allMethodsCache = methods;
        return methods;
    }

    // ── Pointcut resolution ──────────────────────────────────────────

    private List<String> resolvePointcut(String pointcutExpression, List<MethodInfo> allMethods) {
        if (pointcutExpression == null || pointcutExpression.isBlank()) return List.of();

        List<String> matched = new ArrayList<>();
        String expression = pointcutExpression.trim();

        for (String part : splitPointcutParts(expression)) {
            part = part.trim();
            if (part.isEmpty()) continue;

            if (part.startsWith("execution(")) {
                matchExecution(part, allMethods, matched);
            } else if (part.startsWith("@annotation(")) {
                matchAnnotationPointcut(part, allMethods, matched);
            } else if (part.startsWith("within(")) {
                matchWithin(part, allMethods, matched);
            } else if (part.startsWith("@within(")) {
                matchWithinAnnotation(part, allMethods, matched);
            }
        }

        return matched;
    }

    private List<String> splitPointcutParts(String expression) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '&' && i + 1 < expression.length() && expression.charAt(i + 1) == '&' && depth == 0) {
                parts.add(expression.substring(start, i));
                start = i + 2;
                i++;
            } else if (c == '|' && i + 1 < expression.length() && expression.charAt(i + 1) == '|' && depth == 0) {
                parts.add(expression.substring(start, i));
                start = i + 2;
                i++;
            } else if (c == '!' && depth == 0) {
                if (start < i) parts.add(expression.substring(start, i));
                start = i + 1;
            }
        }
        if (start < expression.length()) {
            parts.add(expression.substring(start));
        }
        return parts;
    }

    private void matchExecution(String pointcut, List<MethodInfo> allMethods, List<String> matched) {
        int openParen = pointcut.indexOf('(');
        int closeParen = pointcut.lastIndexOf(')');
        if (openParen < 0 || closeParen < 0) return;

        String pattern = pointcut.substring(openParen + 1, closeParen).trim();
        if (pattern.startsWith("execution(")) {
            matchExecution(pattern, allMethods, matched);
            return;
        }

        String regex = pointcutPatternToRegex(pattern);

        for (MethodInfo mi : allMethods) {
            String fullName = mi.qualifiedClassName() + "." + mi.methodName();
            if (Pattern.matches(regex, fullName)) {
                matched.add(mi.signature());
            }
        }
    }

    private String pointcutPatternToRegex(String pattern) {
        // Strip modifiers (public, protected, private, static, etc.)
        String cleaned = pattern.replaceAll("\\b(public|protected|private|static|final|synchronized|abstract|native|strictfp)\\s+", "");

        // Find the last space before the first '(' — that's the method pattern
        int lastSpace = cleaned.lastIndexOf(' ');
        String methodPattern;
        if (lastSpace > 0) {
            methodPattern = cleaned.substring(lastSpace + 1).trim();
        } else {
            methodPattern = cleaned.trim();
        }

        // Convert AspectJ pattern to regex
        String regex = methodPattern
                .replace(".", "\\.")
                .replace("**", "___DOUBLE_STAR___")
                .replace("*", "[^.]*")
                .replace("___DOUBLE_STAR___", ".*")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("..,", "[^)]*,")
                .replace(",..", ",[^)]*")
                .replace("..", ".*");

        return "^" + regex + "$";
    }

    private void matchAnnotationPointcut(String pointcut, List<MethodInfo> allMethods, List<String> matched) {
        int open = pointcut.indexOf('(');
        int close = pointcut.lastIndexOf(')');
        if (open < 0 || close < 0) return;

        String annotationFqn = pointcut.substring(open + 1, close).trim();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

            PsiClass[] javaClasses = AllClassesSearch.search(scope, project)
                    .findAll()
                    .toArray(PsiClass[]::new);

            for (PsiClass psiClass : javaClasses) {
                for (PsiMethod method : psiClass.getMethods()) {
                    for (PsiAnnotation annotation : method.getAnnotations()) {
                        String fqn = annotation.getQualifiedName();
                        if (annotationFqn.equals(fqn)) {
                            matched.add(CallGraphExtractor.buildSignature(psiClass, method));
                            break;
                        }
                    }
                }
            }

            List<KtFile> ktFiles = KotlinPsiHelper.findKotlinFiles(project, scope);
            for (KtFile ktFile : ktFiles) {
                for (KtClassOrObject ktClass : KotlinPsiHelper.getKotlinClasses(ktFile)) {
                    for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(ktClass)) {
                        for (KtAnnotationEntry entry : func.getAnnotationEntries()) {
                            String fqn = KotlinPsiHelper.getAnnotationFqn(entry);
                            if (annotationFqn.equals(fqn)) {
                                matched.add(KotlinPsiHelper.buildKotlinSignature(ktClass, func));
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void matchWithin(String pointcut, List<MethodInfo> allMethods, List<String> matched) {
        int open = pointcut.indexOf('(');
        int close = pointcut.lastIndexOf(')');
        if (open < 0 || close < 0) return;

        String classPattern = pointcut.substring(open + 1, close).trim();
        String regex = classPattern
                .replace(".", "\\.")
                .replace("*", "[^.]*")
                .replace("..", ".*");

        for (MethodInfo mi : allMethods) {
            if (Pattern.matches(regex, mi.qualifiedClassName())) {
                matched.add(mi.signature());
            }
        }
    }

    private void matchWithinAnnotation(String pointcut, List<MethodInfo> allMethods, List<String> matched) {
        int open = pointcut.indexOf('(');
        int close = pointcut.lastIndexOf(')');
        if (open < 0 || close < 0) return;

        String annotationFqn = pointcut.substring(open + 1, close).trim();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

            PsiClass[] javaClasses = AllClassesSearch.search(scope, project)
                    .findAll()
                    .toArray(PsiClass[]::new);

            for (PsiClass psiClass : javaClasses) {
                boolean hasAnnotation = false;
                for (PsiAnnotation annotation : psiClass.getAnnotations()) {
                    if (annotationFqn.equals(annotation.getQualifiedName())) {
                        hasAnnotation = true;
                        break;
                    }
                }
                if (!hasAnnotation) continue;

                for (PsiMethod method : psiClass.getMethods()) {
                    matched.add(CallGraphExtractor.buildSignature(psiClass, method));
                }
            }
        }
    }

    // ── Java advice ───────────────────────────────────────────────────

    private Map<String, Object> extractAdviceBindingJava(String aspectClassName, PsiMethod method,
                                                          List<MethodInfo> allMethods) {
        String adviceType = null;
        String pointcutExpression = null;

        for (PsiAnnotation annotation : method.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (fqn == null) continue;

            adviceType = mapAdviceType(fqn);
            if (adviceType != null) {
                pointcutExpression = extractPointcutValueJava(annotation);
                break;
            }
        }

        if (adviceType == null) return null;

        List<String> matchedSigs = resolvePointcut(pointcutExpression, allMethods);

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("aspectClassName", aspectClassName);
        binding.put("aspectMethodName", method.getName());
        binding.put("adviceType", adviceType);
        binding.put("pointcutExpression", pointcutExpression != null ? pointcutExpression : "");
        binding.put("matchedMethodSignatures", matchedSigs);
        return binding;
    }

    private String extractPointcutValueJava(PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("value");
        if (value != null) return value.getText().replace("\"", "");
        PsiAnnotationMemberValue pointcut = annotation.findDeclaredAttributeValue("pointcut");
        if (pointcut != null) return pointcut.getText().replace("\"", "");
        return "";
    }

    // ── Kotlin advice ─────────────────────────────────────────────────

    private Map<String, Object> extractAdviceBindingKotlin(String aspectClassName, KtNamedFunction func,
                                                            List<MethodInfo> allMethods) {
        String adviceType = null;
        String pointcutExpression = null;

        for (KtAnnotationEntry entry : func.getAnnotationEntries()) {
            String fqn = KotlinPsiHelper.getAnnotationFqn(entry);
            if (fqn == null) continue;

            adviceType = mapAdviceType(fqn);
            if (adviceType != null) {
                pointcutExpression = extractPointcutValueKotlin(entry);
                break;
            }
        }

        if (adviceType == null) return null;

        List<String> matchedSigs = resolvePointcut(pointcutExpression, allMethods);

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("aspectClassName", aspectClassName);
        binding.put("aspectMethodName", func.getName());
        binding.put("adviceType", adviceType);
        binding.put("pointcutExpression", pointcutExpression != null ? pointcutExpression : "");
        binding.put("matchedMethodSignatures", matchedSigs);
        return binding;
    }

    private String extractPointcutValueKotlin(KtAnnotationEntry entry) {
        for (ValueArgument va : entry.getValueArguments()) {
            String name = va.getArgumentName() != null
                ? va.getArgumentName().getAsName().asString() : "value";
            if ("value".equals(name) || "pointcut".equals(name)) {
                KtExpression expr = va.getArgumentExpression();
                if (expr != null) return expr.getText().replace("\"", "");
            }
        }
        return "";
    }

    // ── Common ────────────────────────────────────────────────────────

    private String mapAdviceType(String fqn) {
        return switch (fqn) {
            case "org.aspectj.lang.annotation.Before" -> "Before";
            case "org.aspectj.lang.annotation.Around" -> "Around";
            case "org.aspectj.lang.annotation.After" -> "After";
            case "org.aspectj.lang.annotation.AfterReturning" -> "AfterReturning";
            case "org.aspectj.lang.annotation.AfterThrowing" -> "AfterThrowing";
            default -> null;
        };
    }

    private boolean hasJavaAnnotation(PsiClass psiClass, String annotationFqn) {
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (annotationFqn.equals(fqn)) return true;
        }
        return false;
    }
}
