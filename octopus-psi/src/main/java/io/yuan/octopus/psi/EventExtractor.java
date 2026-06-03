package io.yuan.octopus.psi;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.*;

import java.util.*;

/**
 * Extracts Spring event publisher-to-listener bindings from Java and Kotlin.
 */
public class EventExtractor {

    private final Project project;

    public EventExtractor(Project project) {
        this.project = project;
    }

    public List<Map<String, Object>> extract() {
        List<Map<String, Object>> bindings = new ArrayList<>();
        Map<String, List<String>> eventTypeToListeners = buildEventListenerMap();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

            // ── Java publishers ──
            PsiClass[] javaClasses = AllClassesSearch.search(scope, project)
                .findAll()
                .toArray(PsiClass[]::new);

            for (PsiClass psiClass : javaClasses) {
                for (PsiMethod method : psiClass.getMethods()) {
                    if (method.getBody() == null) continue;
                    Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(
                        method.getBody(), PsiMethodCallExpression.class);

                    for (PsiMethodCallExpression call : calls) {
                        if (!"publishEvent".equals(call.getMethodExpression().getReferenceName())) continue;
                        PsiExpression[] args = call.getArgumentList().getExpressions();
                        if (args.length == 0) continue;
                        PsiType eventType = args[0].getType();
                        if (eventType == null) continue;

                        addBinding(bindings, psiClass, method, eventType.getCanonicalText(), eventTypeToListeners);
                    }
                }
            }

            // ── Kotlin publishers ──
            List<KtFile> ktFiles = KotlinPsiHelper.findKotlinFiles(project, scope);
            for (KtFile ktFile : ktFiles) {
                for (KtClassOrObject ktClass : KotlinPsiHelper.getKotlinClasses(ktFile)) {
                    for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(ktClass)) {
                        KtExpression body = func.getBodyExpression();
                        if (body == null) continue;

                        Collection<KtCallExpression> calls = PsiTreeUtil.findChildrenOfType(body, KtCallExpression.class);
                        for (KtCallExpression call : calls) {
                            String callName = KotlinPsiHelper.getCallName(call);
                            if (!"publishEvent".equals(callName)) continue;

                            List<KtValueArgument> args = call.getValueArguments();
                            if (args.isEmpty()) continue;

                            KtExpression firstArg = args.get(0).getArgumentExpression();
                            if (firstArg == null) continue;

                            String eventTypeName = firstArg.getText();
                            String publisherSig = KotlinPsiHelper.buildKotlinSignature(ktClass, func);
                            List<String> listeners = eventTypeToListeners.getOrDefault(eventTypeName, List.of());

                            Map<String, Object> binding = new LinkedHashMap<>();
                            binding.put("eventType", eventTypeName);
                            binding.put("publisherMethodSignatures", List.of(publisherSig));
                            binding.put("listenerMethodSignatures", listeners);
                            bindings.add(binding);
                        }
                    }
                }
            }
        }

        return bindings;
    }

    private void addBinding(List<Map<String, Object>> bindings, PsiClass psiClass, PsiMethod method,
                            String eventTypeName, Map<String, List<String>> eventTypeToListeners) {
        String publisherSig = CallGraphExtractor.buildSignature(psiClass, method);
        List<String> listeners = eventTypeToListeners.getOrDefault(eventTypeName, List.of());

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("eventType", eventTypeName);
        binding.put("publisherMethodSignatures", List.of(publisherSig));
        binding.put("listenerMethodSignatures", listeners);
        bindings.add(binding);
    }

    // ── Event listener map ────────────────────────────────────────────

    private Map<String, List<String>> buildEventListenerMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

            // Java listeners
            PsiClass[] javaClasses = AllClassesSearch.search(scope, project)
                .findAll()
                .toArray(PsiClass[]::new);

            for (PsiClass psiClass : javaClasses) {
                for (PsiMethod method : psiClass.getMethods()) {
                    if (!hasEventListenerAnnotationJava(method)) continue;
                    PsiParameter[] params = method.getParameterList().getParameters();
                    if (params.length == 0) continue;
                    PsiType eventType = params[0].getType();
                    String eventTypeName = eventType.getCanonicalText();
                    String listenerSig = CallGraphExtractor.buildSignature(psiClass, method);
                    map.computeIfAbsent(eventTypeName, k -> new ArrayList<>()).add(listenerSig);
                }
            }

            // Kotlin listeners
            List<KtFile> ktFiles = KotlinPsiHelper.findKotlinFiles(project, scope);
            for (KtFile ktFile : ktFiles) {
                for (KtClassOrObject ktClass : KotlinPsiHelper.getKotlinClasses(ktFile)) {
                    for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(ktClass)) {
                        if (!hasEventListenerAnnotationKotlin(func)) continue;
                        List<KtParameter> params = func.getValueParameters();
                        if (params.isEmpty()) continue;
                        String eventTypeName = params.get(0).getTypeReference() != null
                            ? params.get(0).getTypeReference().getText() : "kotlin.Any";
                        String listenerSig = KotlinPsiHelper.buildKotlinSignature(ktClass, func);
                        map.computeIfAbsent(eventTypeName, k -> new ArrayList<>()).add(listenerSig);
                    }
                }
            }
        }

        return map;
    }

    private boolean hasEventListenerAnnotationJava(PsiMethod method) {
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if ("org.springframework.context.event.EventListener".equals(fqn)) return true;
        }
        return false;
    }

    private boolean hasEventListenerAnnotationKotlin(KtNamedFunction func) {
        for (KtAnnotationEntry entry : func.getAnnotationEntries()) {
            String fqn = KotlinPsiHelper.getAnnotationFqn(entry);
            if ("org.springframework.context.event.EventListener".equals(fqn)) return true;
        }
        return false;
    }
}
