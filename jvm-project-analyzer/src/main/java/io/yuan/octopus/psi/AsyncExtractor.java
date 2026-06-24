package io.yuan.octopus.psi;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

/**
 * Extracts Spring @Async method bindings.
 */
@SuppressWarnings("deprecation")
public class AsyncExtractor {

    private static final String ASYNC_ANNOTATION = "org.springframework.scheduling.annotation.Async";
    private final Project project;

    public AsyncExtractor(Project project) { this.project = project; }

    public List<Map<String, Object>> extract() {
        List<Map<String, Object>> bindings = new ArrayList<>();
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);
            for (PsiClass psiClass : AllClassesSearch.search(scope, project).findAll()) {
                for (PsiMethod method : psiClass.getMethods()) {
                    if (!hasAsyncAnnotation(method)) continue;
                    String sig = CallGraphExtractor.buildSignature(psiClass, method);
                    List<String> callers = findCallers(method, scope);
                    if (callers.isEmpty()) continue;
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("asyncMethodSignature", sig);
                    b.put("callerMethodSignatures", callers);
                    bindings.add(b);
                }
            }
        }
        return bindings;
    }

    private boolean hasAsyncAnnotation(PsiMethod method) {
        for (PsiAnnotation a : method.getAnnotations()) {
            if (ASYNC_ANNOTATION.equals(a.getQualifiedName())) return true;
        }
        return false;
    }

    private List<String> findCallers(PsiMethod method, GlobalSearchScope scope) {
        List<String> r = new ArrayList<>();
        ReferencesSearch.search(method, scope).forEach(ref -> {
            PsiElement el = ref.getElement();
            PsiMethod m = PsiTreeUtil.getParentOfType(el, PsiMethod.class);
            if (m != null && m.getContainingClass() != null)
                r.add(CallGraphExtractor.buildSignature(m.getContainingClass(), m));
        });
        return r;
    }
}
