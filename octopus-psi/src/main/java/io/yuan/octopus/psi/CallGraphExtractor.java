package io.yuan.octopus.psi;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.psi.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts a method-level call graph from the PSI tree of a project.
 * <p>
 * Traverses all Java and Kotlin source files, identifies method definitions
 * as nodes, and method call expressions (including Spring-aware DI resolution)
 * as edges.
 */
public class CallGraphExtractor {

    private static final Logger LOG = Logger.getInstance(CallGraphExtractor.class);

    private final Project project;
    private final SpringModelExtractor springExtractor;
    private final Set<String> visitedMethods = new HashSet<>();

    public CallGraphExtractor(Project project, SpringModelExtractor springExtractor) {
        this.project = project;
        this.springExtractor = springExtractor;
    }

    /**
     * Main extraction entry point.
     */
    public CallGraphResult extract() {
        springExtractor.extract();

        List<Map<String, Object>> methods = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            extractFromModule(module, methods, edges);
        }

        addSpringDIEdges(methods, edges);

        return new CallGraphResult(methods, edges);
    }

    private void extractFromModule(Module module, List<Map<String, Object>> methods, List<Map<String, Object>> edges) {
        GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

        // ── Java classes ──
        PsiClass[] javaClasses = AllClassesSearch.search(scope, project)
            .findAll()
            .toArray(PsiClass[]::new);

        for (PsiClass psiClass : javaClasses) {
            extractJavaClass(psiClass, methods, edges);
        }

        // ── Kotlin classes ──
        List<KtFile> ktFiles = KotlinPsiHelper.findKotlinFiles(project, scope);
        for (KtFile ktFile : ktFiles) {
            for (KtClassOrObject ktClass : KotlinPsiHelper.getKotlinClasses(ktFile)) {
                extractKotlinClass(ktClass, methods, edges);
            }
        }
    }

    // ── Java class extraction ─────────────────────────────────────────

    private void extractJavaClass(PsiClass psiClass, List<Map<String, Object>> methods, List<Map<String, Object>> edges) {
        String qualifiedClassName = psiClass.getQualifiedName();
        if (qualifiedClassName == null) return;

        for (PsiMethod method : psiClass.getMethods()) {
            String signature = buildSignature(psiClass, method);
            if (!visitedMethods.add(signature)) continue;

            methods.add(buildMethodNode(psiClass, method));

            if (method.getBody() != null) {
                extractJavaCalls(method, qualifiedClassName, signature, edges);
            }
        }
    }

    private void extractJavaCalls(PsiMethod method, String callerClassName, String callerSignature,
                                  List<Map<String, Object>> edges) {
        Collection<PsiMethodCallExpression> callExpressions = PsiTreeUtil.findChildrenOfType(
            method.getBody(), PsiMethodCallExpression.class);

        for (PsiMethodCallExpression callExpr : callExpressions) {
            PsiMethod resolvedMethod = callExpr.resolveMethod();
            if (resolvedMethod == null) {
                // Fallback: try to resolve the method from the qualifier/scope.
                // resolveMethod() can return null for static cross-module calls
                // even when the target class is indexed and available.
                resolvedMethod = resolveViaQualifier(callExpr, project);
                if (resolvedMethod == null) {
                    String callText = callExpr.getText();
                    if (callText != null && callText.length() > 80) {
                        callText = callText.substring(0, 80);
                    }
                    LOG.warn("resolveMethod() null for call: '" + callText + "' in " + callerClassName + "." + method.getName() + " L" + getLineNumber(callExpr));
                    continue;
                }
            }

            PsiClass targetClass = resolvedMethod.getContainingClass();
            if (targetClass == null) continue;

            String targetClassName = targetClass.getQualifiedName();
            if (targetClassName == null) continue;

            String calleeSignature = buildSignature(targetClass, resolvedMethod);
            String callType = determineJavaCallType(callExpr, resolvedMethod);

            edges.add(buildEdge(callerSignature, calleeSignature, callType, getLineNumber(callExpr)));
        }
    }

    // ── Kotlin class extraction ───────────────────────────────────────

    private void extractKotlinClass(KtClassOrObject ktClass, List<Map<String, Object>> methods, List<Map<String, Object>> edges) {
        String qualifiedClassName = KotlinPsiHelper.getKotlinClassFqn(ktClass);

        for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(ktClass)) {
            String signature = KotlinPsiHelper.buildKotlinSignature(ktClass, func);
            if (!visitedMethods.add(signature)) continue;

            methods.add(buildKotlinMethodNode(ktClass, func));

            KtExpression body = func.getBodyExpression();
            if (body != null) {
                extractKotlinCalls(func, qualifiedClassName, signature, body, edges);
            }
        }
    }

    private void extractKotlinCalls(KtNamedFunction func, String callerClassName, String callerSignature,
                                    KtExpression body, List<Map<String, Object>> edges) {
        Collection<KtCallExpression> calls = PsiTreeUtil.findChildrenOfType(body, KtCallExpression.class);
        Collection<KtDotQualifiedExpression> qualifiedCalls = PsiTreeUtil.findChildrenOfType(body, KtDotQualifiedExpression.class);

        for (KtCallExpression callExpr : calls) {
            String calleeName = KotlinPsiHelper.getCallName(callExpr);
            String receiverText = KotlinPsiHelper.getReceiverText(callExpr);

            // Build a best-effort callee signature from the call text
            String calleeSignature = callerClassName + "." + calleeName + "():" + "kotlin.Any";
            String callType = receiverText != null && "super".equals(receiverText) ? "SUPER" : "DIRECT";

            edges.add(buildEdge(callerSignature, calleeSignature, callType, getLineNumber(callExpr)));
        }
    }

    // ── DI edges ──────────────────────────────────────────────────────

    private void addSpringDIEdges(List<Map<String, Object>> methods, List<Map<String, Object>> edges) {
        Module[] modules = ModuleManager.getInstance(project).getModules();

        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

            // Java fields
            PsiClass[] javaClasses = AllClassesSearch.search(scope, project)
                .findAll()
                .toArray(PsiClass[]::new);

            for (PsiClass psiClass : javaClasses) {
                addJavaDIEdges(psiClass, edges);
                addJavaConstructorDIEdges(psiClass, edges);
                addJavaSetterDIEdges(psiClass, edges);
            }

            // Kotlin properties
            List<KtFile> ktFiles = KotlinPsiHelper.findKotlinFiles(project, scope);
            for (KtFile ktFile : ktFiles) {
                for (KtClassOrObject ktClass : KotlinPsiHelper.getKotlinClasses(ktFile)) {
                    addKotlinDIEdges(ktClass, edges);
                }
            }
        }
    }

    private void addJavaDIEdges(PsiClass psiClass, List<Map<String, Object>> edges) {
        String className = psiClass.getQualifiedName();
        if (className == null) return;

        for (PsiField field : psiClass.getFields()) {
            if (!isAutowired(field)) continue;

            PsiType fieldType = field.getType();
            String implClassName = springExtractor.resolveBeanImplementation(fieldType);
            if (implClassName == null) continue;

            for (PsiMethod method : psiClass.getMethods()) {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("callerSignature", buildSignature(psiClass, method));
                edge.put("calleeClassName", implClassName);
                edge.put("calleeFieldName", field.getName());
                edge.put("callType", "SPRING_DI");
                edge.put("lineNumber", -1);
                edges.add(edge);
            }
        }
    }

    private void addKotlinDIEdges(KtClassOrObject ktClass, List<Map<String, Object>> edges) {
        for (KtProperty property : KotlinPsiHelper.getKotlinProperties(ktClass)) {
            if (!isAutowiredKotlin(property)) continue;

            KtTypeReference typeRef = property.getTypeReference();
            if (typeRef == null) continue;

            String typeText = typeRef.getText();
            if (typeText.endsWith("?")) typeText = typeText.substring(0, typeText.length() - 1);
            String implClassName = springExtractor.resolveBeanImplementationByFqn(typeText);
            if (implClassName == null) continue;

            for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(ktClass)) {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("callerSignature", KotlinPsiHelper.buildKotlinSignature(ktClass, func));
                edge.put("calleeClassName", implClassName);
                edge.put("calleeFieldName", property.getName());
                edge.put("callType", "SPRING_DI");
                edge.put("lineNumber", -1);
                edges.add(edge);
            }
        }
    }

    private void addJavaConstructorDIEdges(PsiClass psiClass, List<Map<String, Object>> edges) {
        String className = psiClass.getQualifiedName();
        if (className == null) return;

        for (PsiMethod ctor : psiClass.getConstructors()) {
            if (!isConstructorAutowired(ctor) && psiClass.getConstructors().length != 1) continue;

            PsiParameter[] params = ctor.getParameterList().getParameters();
            for (PsiParameter param : params) {
                String implClassName = springExtractor.resolveBeanImplementation(param.getType());
                if (implClassName == null) continue;

                for (PsiMethod method : psiClass.getMethods()) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("callerSignature", buildSignature(psiClass, method));
                    edge.put("calleeClassName", implClassName);
                    edge.put("calleeFieldName", param.getName());
                    edge.put("callType", "SPRING_DI");
                    edge.put("lineNumber", -1);
                    edges.add(edge);
                }
            }
        }
    }

    private void addJavaSetterDIEdges(PsiClass psiClass, List<Map<String, Object>> edges) {
        String className = psiClass.getQualifiedName();
        if (className == null) return;

        for (PsiMethod method : psiClass.getMethods()) {
            if (method.isConstructor()) continue;
            if (!isSetterAutowired(method)) continue;

            PsiParameter[] params = method.getParameterList().getParameters();
            if (params.length == 0) continue;

            for (PsiParameter param : params) {
                String implClassName = springExtractor.resolveBeanImplementation(param.getType());
                if (implClassName == null) continue;

                for (PsiMethod callerMethod : psiClass.getMethods()) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("callerSignature", buildSignature(psiClass, callerMethod));
                    edge.put("calleeClassName", implClassName);
                    edge.put("calleeFieldName", param.getName());
                    edge.put("callType", "SPRING_DI");
                    edge.put("lineNumber", -1);
                    edges.add(edge);
                }
            }
        }
    }

    private boolean isConstructorAutowired(PsiMethod ctor) {
        for (PsiAnnotation annotation : ctor.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (fqn != null && (
                fqn.equals("org.springframework.beans.factory.annotation.Autowired") ||
                fqn.equals("jakarta.inject.Inject") ||
                fqn.equals("javax.inject.Inject"))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSetterAutowired(PsiMethod method) {
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (fqn != null && (
                fqn.equals("org.springframework.beans.factory.annotation.Autowired") ||
                fqn.equals("jakarta.annotation.Resource") ||
                fqn.equals("javax.annotation.Resource") ||
                fqn.equals("jakarta.inject.Inject") ||
                fqn.equals("javax.inject.Inject"))) {
                return true;
            }
        }
        return false;
    }

    // ── Annotation checks ─────────────────────────────────────────────

    private boolean isAutowired(PsiField field) {
        for (PsiAnnotation annotation : field.getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null &&
                (qualifiedName.equals("org.springframework.beans.factory.annotation.Autowired") ||
                 qualifiedName.equals("jakarta.annotation.Resource") ||
                 qualifiedName.equals("javax.annotation.Resource") ||
                 qualifiedName.equals("jakarta.inject.Inject") ||
                 qualifiedName.equals("javax.inject.Inject"))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAutowiredKotlin(KtProperty property) {
        for (KtAnnotationEntry entry : property.getAnnotationEntries()) {
            String fqn = KotlinPsiHelper.getAnnotationFqn(entry);
            if (fqn != null &&
                (fqn.equals("org.springframework.beans.factory.annotation.Autowired") ||
                 fqn.equals("jakarta.annotation.Resource") ||
                 fqn.equals("javax.annotation.Resource") ||
                 fqn.equals("jakarta.inject.Inject") ||
                 fqn.equals("javax.inject.Inject"))) {
                return true;
            }
        }
        return false;
    }

    // ── Method node builders ──────────────────────────────────────────

    private Map<String, Object> buildMethodNode(PsiClass psiClass, PsiMethod method) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("qualifiedClassName", psiClass.getQualifiedName());
        node.put("methodName", method.getName());

        List<String> paramTypes = Arrays.stream(method.getParameterList().getParameters())
            .map(p -> p.getType().getPresentableText())
            .collect(Collectors.toList());
        node.put("parameterTypes", paramTypes);

        node.put("returnType", method.getReturnType() != null
            ? method.getReturnType().getPresentableText() : "void");

        node.put("modifiers", method.getModifierList().getText());
        node.put("signature", buildSignature(psiClass, method));
        node.put("sourceFilePath", getSourceFilePath(method));
        node.put("sourceCode", method.getText());

        List<String> springAnnotations = new ArrayList<>();
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name != null && isSpringAnnotation(name)) {
                springAnnotations.add(name);
            }
        }
        node.put("springAnnotations", springAnnotations);

        boolean isBean = false;
        String beanName = null;
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name != null && isStereotypeAnnotation(name)) {
                isBean = true;
                beanName = deriveJavaBeanName(psiClass.getName());
                break;
            }
        }
        node.put("isBean", isBean);
        node.put("beanName", isBean ? beanName : null);

        return node;
    }

    private Map<String, Object> buildKotlinMethodNode(KtClassOrObject ktClass, KtNamedFunction func) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("qualifiedClassName", KotlinPsiHelper.getKotlinClassFqn(ktClass));
        node.put("methodName", func.getName());

        List<String> paramTypes = func.getValueParameters().stream()
            .map(p -> p.getTypeReference() != null ? p.getTypeReference().getText() : "kotlin.Any")
            .collect(Collectors.toList());
        node.put("parameterTypes", paramTypes);

        node.put("returnType", func.getTypeReference() != null
            ? func.getTypeReference().getText() : "kotlin.Unit");

        node.put("modifiers", KotlinPsiHelper.getModifierText(func));
        node.put("signature", KotlinPsiHelper.buildKotlinSignature(ktClass, func));
        node.put("sourceFilePath", getSourceFilePath(func));
        node.put("sourceCode", func.getText());

        List<String> springAnnotations = new ArrayList<>();
        for (KtAnnotationEntry entry : func.getAnnotationEntries()) {
            String fqn = KotlinPsiHelper.getAnnotationFqn(entry);
            if (fqn != null && isSpringAnnotation(fqn)) {
                springAnnotations.add(fqn);
            }
        }
        node.put("springAnnotations", springAnnotations);

        boolean isBean = false;
        String beanName = null;
        for (KtAnnotationEntry entry : ktClass.getAnnotationEntries()) {
            String fqn = KotlinPsiHelper.getAnnotationFqn(entry);
            if (fqn != null && isStereotypeAnnotation(fqn)) {
                isBean = true;
                beanName = deriveJavaBeanName(ktClass.getName());
                break;
            }
        }
        node.put("isBean", isBean);
        node.put("beanName", isBean ? beanName : null);

        return node;
    }

    // ── Signature builders ────────────────────────────────────────────

    static String buildSignature(PsiClass psiClass, PsiMethod method) {
        String className = psiClass.getQualifiedName();
        String methodName = method.getName();
        String params = Arrays.stream(method.getParameterList().getParameters())
            .map(p -> p.getType().getPresentableText())
            .collect(Collectors.joining(","));
        String returnType = method.getReturnType() != null
            ? method.getReturnType().getPresentableText() : "void";
        return className + "." + methodName + "(" + params + "):" + returnType;
    }

    // ── Utilities ─────────────────────────────────────────────────────

    private String getSourceFilePath(PsiElement element) {
        PsiFile file = element.getContainingFile();
        return file != null && file.getVirtualFile() != null ? file.getVirtualFile().getPath() : null;
    }

    /**
     * Fallback method resolution when {@link PsiMethodCallExpression#resolveMethod()}
     * returns null.  Attempts to resolve the call by looking up the qualifier class
     * and finding a matching method by name + parameter count.
     */
    // Cache: simple class name → PsiClass, lazily populated on first need.
    private Map<String, List<PsiClass>> shortNameClassCache = null;

    private Map<String, List<PsiClass>> getShortNameClassCache(Project project) {
        if (shortNameClassCache == null) {
            shortNameClassCache = new java.util.HashMap<>();
            for (PsiClass psiClass : com.intellij.psi.search.searches.AllClassesSearch.search(
                    GlobalSearchScope.allScope(project), project).findAll()) {
                String name = psiClass.getName();
                if (name != null) {
                    shortNameClassCache.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(psiClass);
                }
            }
            LOG.info("Built short-name class cache: " + shortNameClassCache.size() + " names");
        }
        return shortNameClassCache;
    }

    private PsiMethod resolveViaQualifier(PsiMethodCallExpression callExpr, Project project) {
        PsiReferenceExpression methodExpr = callExpr.getMethodExpression();
        PsiExpression qualifier = methodExpr.getQualifierExpression();
        if (qualifier == null) return null;

        String methodName = methodExpr.getReferenceName();
        if (methodName == null) return null;

        PsiExpression[] args = callExpr.getArgumentList().getExpressions();
        String qualifierText = qualifier.getText();
        
        if (qualifierText != null && !qualifierText.isEmpty()) {
            // First try FQN via JavaPsiFacade (e.g. "com.ruoyi.core.domain.AjaxResult")
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            for (PsiClass psiClass : psiFacade.findClasses(qualifierText, GlobalSearchScope.allScope(project))) {
                PsiMethod m = findBestMethod(psiClass, methodName, args);
                if (m != null) return m;
            }

            // Fallback: simple name via cached AllClassesSearch (e.g. "AjaxResult")
            List<PsiClass> candidates = getShortNameClassCache(project).get(qualifierText);
            if (candidates != null) {
                for (PsiClass psiClass : candidates) {
                    PsiMethod m = findBestMethod(psiClass, methodName, args);
                    if (m != null) return m;
                }
            }
        }

        // Approach 2: Resolve via type
        PsiType qualifierType = qualifier.getType();
        if (qualifierType != null) {
            PsiClass typeClass = PsiUtil.resolveClassInType(qualifierType);
            if (typeClass != null) {
                PsiMethod m = findBestMethod(typeClass, methodName, args);
                if (m != null) return m;
            }
        }

        return null;
    }

    private PsiMethod findBestMethod(PsiClass psiClass, String methodName, PsiExpression[] args) {
        PsiMethod[] candidates = psiClass.findMethodsByName(methodName, true);
        if (candidates.length == 0) return null;
        // Prefer method with matching parameter count
        for (PsiMethod m : candidates) {
            if (m.getParameterList().getParametersCount() == args.length) {
                return m;
            }
        }
        // Fallback to first match
        return candidates[0];
    }

    private Map<String, Object> buildEdge(String callerSig, String calleeSig, String callType, int lineNumber) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("callerSignature", callerSig);
        edge.put("calleeSignature", calleeSig);
        edge.put("callType", callType);
        edge.put("lineNumber", lineNumber);
        return edge;
    }

    private String determineJavaCallType(PsiMethodCallExpression callExpr, PsiMethod resolvedMethod) {
        PsiClass targetClass = resolvedMethod.getContainingClass();
        if (targetClass != null && targetClass.isInterface()) {
            return "INTERFACE";
        }
        PsiExpression qualifier = callExpr.getMethodExpression().getQualifierExpression();
        if (qualifier instanceof PsiSuperExpression) {
            return "SUPER";
        }
        return "DIRECT";
    }

    private int getLineNumber(PsiElement element) {
        return ReadAction.compute(() -> {
            try {
                return PsiDocumentManager.getInstance(project)
                    .getDocument(element.getContainingFile())
                    .getLineNumber(element.getTextOffset()) + 1;
            } catch (Exception e) {
                return 0;
            }
        });
    }

    private boolean isSpringAnnotation(String fqn) {
        return fqn.startsWith("org.springframework.");
    }

    private boolean isStereotypeAnnotation(String fqn) {
        return fqn.equals("org.springframework.stereotype.Component") ||
               fqn.equals("org.springframework.stereotype.Service") ||
               fqn.equals("org.springframework.stereotype.Repository") ||
               fqn.equals("org.springframework.stereotype.Controller") ||
               fqn.equals("org.springframework.web.bind.annotation.RestController");
    }

    private String deriveJavaBeanName(String simpleName) {
        if (simpleName == null) return "";
        if (simpleName.length() == 1) return simpleName.toLowerCase(Locale.ROOT);
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
