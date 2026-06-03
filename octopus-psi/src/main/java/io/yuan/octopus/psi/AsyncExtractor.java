package io.yuan.octopus.psi;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.*;

import java.util.*;

public class AsyncExtractor {

    private static final String ASYNC_ANNOTATION = "org.springframework.scheduling.annotation.Async";

    private final Project project;

    public AsyncExtractor(Project project) {
        this.project = project;
    }

    public List<Map<String, Object>> extract() {
        List<Map<String, Object>> bindings = new ArrayList<>();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

            PsiClass[] javaClasses = AllClassesSearch.search(scope, project)
                    .findAll()
                    .toArray(PsiClass[]::new);

            for (PsiClass psiClass : javaClasses) {
                for (PsiMethod method : psiClass.getMethods()) {
                    if (!hasAsyncAnnotationJava(method)) continue;

                    String asyncSig = CallGraphExtractor.buildSignature(psiClass, method);
                    List<String> callerSigs = findJavaMethodCallers(method, scope);
                    if (callerSigs.isEmpty()) continue;

                    Map<String, Object> binding = new LinkedHashMap<>();
                    binding.put("asyncMethodSignature", asyncSig);
                    binding.put("callerMethodSignatures", callerSigs);
                    bindings.add(binding);
                }
            }

            List<KtFile> ktFiles = KotlinPsiHelper.findKotlinFiles(project, scope);
            for (KtFile ktFile : ktFiles) {
                for (KtClassOrObject ktClass : KotlinPsiHelper.getKotlinClasses(ktFile)) {
                    for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(ktClass)) {
                        if (!hasAsyncAnnotationKotlin(func)) continue;

                        String asyncSig = KotlinPsiHelper.buildKotlinSignature(ktClass, func);
                        List<String> callerSigs = findKotlinFunctionCallers(func, scope);
                        if (callerSigs.isEmpty()) continue;

                        Map<String, Object> binding = new LinkedHashMap<>();
                        binding.put("asyncMethodSignature", asyncSig);
                        binding.put("callerMethodSignatures", callerSigs);
                        bindings.add(binding);
                    }
                }
            }
        }

        return bindings;
    }

    private boolean hasAsyncAnnotationJava(PsiMethod method) {
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (ASYNC_ANNOTATION.equals(fqn)) return true;
        }
        return false;
    }

    private boolean hasAsyncAnnotationKotlin(KtNamedFunction func) {
        for (KtAnnotationEntry entry : func.getAnnotationEntries()) {
            String fqn = KotlinPsiHelper.getAnnotationFqn(entry);
            if (ASYNC_ANNOTATION.equals(fqn)) return true;
        }
        return false;
    }

    private List<String> findJavaMethodCallers(PsiMethod method, GlobalSearchScope scope) {
        List<String> callers = new ArrayList<>();
        ReferencesSearch.search(method, scope).forEach(ref -> {
            PsiElement element = ref.getElement();
            PsiMethod callerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (callerMethod != null) {
                PsiClass callerClass = callerMethod.getContainingClass();
                if (callerClass != null) {
                    callers.add(CallGraphExtractor.buildSignature(callerClass, callerMethod));
                }
            }
        });
        return callers;
    }

    private List<String> findKotlinFunctionCallers(KtNamedFunction func, GlobalSearchScope scope) {
        List<String> callers = new ArrayList<>();
        ReferencesSearch.search(func, scope).forEach(ref -> {
            PsiElement element = ref.getElement();
            KtNamedFunction callerFunc = PsiTreeUtil.getParentOfType(element, KtNamedFunction.class);
            if (callerFunc != null) {
                KtClassOrObject callerClass = PsiTreeUtil.getParentOfType(callerFunc, KtClassOrObject.class);
                if (callerClass != null) {
                    callers.add(KotlinPsiHelper.buildKotlinSignature(callerClass, callerFunc));
                }
            } else {
                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                if (callerMethod != null) {
                    PsiClass callerClass = callerMethod.getContainingClass();
                    if (callerClass != null) {
                        callers.add(CallGraphExtractor.buildSignature(callerClass, callerMethod));
                    }
                }
            }
        });
        return callers;
    }
}
