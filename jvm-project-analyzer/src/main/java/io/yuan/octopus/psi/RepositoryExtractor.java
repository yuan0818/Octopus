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

@SuppressWarnings("deprecation")
public class RepositoryExtractor {

    private final Project project;

    public RepositoryExtractor(Project project) { this.project = project; }

    public List<Map<String, Object>> extract() {
        List<Map<String, Object>> bindings = new ArrayList<>();
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);
            for (PsiClass psiClass : AllClassesSearch.search(scope, project).findAll()) {
                if (!isRepo(psiClass)) continue;
                String repoName = psiClass.getQualifiedName();
                if (repoName == null) continue;
                List<String> methods = new ArrayList<>();
                for (PsiMethod m : psiClass.getMethods()) {
                    methods.add(CallGraphExtractor.buildSignature(psiClass, m));
                }
                List<String> callers = new ArrayList<>();
                ReferencesSearch.search(psiClass, scope).forEach(ref -> {
                    PsiElement el = ref.getElement();
                    PsiMethod cm = PsiTreeUtil.getParentOfType(el, PsiMethod.class);
                    if (cm != null && cm.getContainingClass() != null)
                        callers.add(CallGraphExtractor.buildSignature(cm.getContainingClass(), cm));
                });
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("repositoryInterfaceName", repoName);
                b.put("domainClassName", extractDomainClass(psiClass));
                b.put("generatedMethodSignatures", methods);
                b.put("callerMethodSignatures", callers);
                bindings.add(b);
            }
        }
        return bindings;
    }

    private String extractDomainClass(PsiClass repoInterface) {
        // Extract domain class from JpaRepository<DomainClass, ID> or similar
        PsiClassType[] extendsTypes = repoInterface.getExtendsListTypes();
        for (PsiClassType type : extendsTypes) {
            PsiClass resolved = type.resolve();
            if (resolved != null && resolved.getQualifiedName() != null
                    && resolved.getQualifiedName().contains("Repository")) {
                PsiType[] params = type.getParameters();
                if (params.length > 0) return params[0].getCanonicalText();
            }
        }
        return "";
    }

    private boolean isRepo(PsiClass psiClass) {
        if (!psiClass.isInterface()) return false;
        for (PsiClass sc : psiClass.getSupers()) {
            if (sc.getQualifiedName() != null && sc.getQualifiedName().contains("Repository")) return true;
        }
        return false;
    }
}
