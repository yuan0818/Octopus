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

/**
 * Extracts Spring Data Repository method bindings from Java and Kotlin.
 */
public class RepositoryExtractor {

    private final Project project;

    private static final String JPA_REPOSITORY = "org.springframework.data.jpa.repository.JpaRepository";
    private static final String CRUD_REPOSITORY = "org.springframework.data.repository.CrudRepository";
    private static final String PAGING_REPOSITORY = "org.springframework.data.repository.PagingAndSortingRepository";

    private static final List<String> STANDARD_REPO_METHODS = List.of(
        "findAll", "findById", "save", "saveAll", "delete", "deleteById",
        "deleteAll", "count", "existsById", "flush", "saveAndFlush",
        "findAllById", "deleteAllById", "getReferenceById", "getOne", "getById"
    );

    public RepositoryExtractor(Project project) {
        this.project = project;
    }

    public List<Map<String, Object>> extract() {
        List<Map<String, Object>> bindings = new ArrayList<>();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

            // ── Java repositories ──
            PsiClass[] javaInterfaces = AllClassesSearch.search(scope, project)
                .findAll().stream()
                .filter(PsiClass::isInterface)
                .toArray(PsiClass[]::new);

            for (PsiClass psiClass : javaInterfaces) {
                if (!isJavaRepositoryInterface(psiClass)) continue;
                addRepositoryBinding(bindings, psiClass.getQualifiedName(),
                    extractJavaDomainClassName(psiClass),
                    getJavaMethodSignatures(psiClass),
                    findJavaRepositoryCallers(psiClass, scope));
            }

            // ── Kotlin repositories ──
            List<KtFile> ktFiles = KotlinPsiHelper.findKotlinFiles(project, scope);
            for (KtFile ktFile : ktFiles) {
                for (KtClassOrObject ktClass : KotlinPsiHelper.getKotlinClasses(ktFile)) {
                    if (!isKotlinInterface(ktClass)) continue;
                    if (!isKotlinRepositoryInterface(ktClass)) continue;

                    String repoName = KotlinPsiHelper.getKotlinClassFqn(ktClass);
                    addRepositoryBinding(bindings, repoName,
                        extractKotlinDomainClassName(ktClass),
                        getKotlinMethodSignatures(ktClass, repoName),
                        findKotlinRepositoryCallers(ktClass, scope));
                }
            }
        }

        return bindings;
    }

    private void addRepositoryBinding(List<Map<String, Object>> bindings, String repoName,
                                       String domainClass, List<String> methodSigs, List<String> callers) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("repositoryInterfaceName", repoName);
        binding.put("domainClassName", domainClass != null ? domainClass : "");
        binding.put("generatedMethodSignatures", methodSigs);
        binding.put("callerMethodSignatures", callers);
        bindings.add(binding);
    }

    // ── Java helpers ──────────────────────────────────────────────────

    private boolean isJavaRepositoryInterface(PsiClass psiClass) {
        for (PsiClass superClass : psiClass.getSupers()) {
            String fqn = superClass.getQualifiedName();
            if (JPA_REPOSITORY.equals(fqn) || CRUD_REPOSITORY.equals(fqn) || PAGING_REPOSITORY.equals(fqn)) {
                return true;
            }
        }
        return false;
    }

    private String extractJavaDomainClassName(PsiClass psiClass) {
        for (PsiClassType superType : psiClass.getExtendsListTypes()) {
            PsiClass resolved = superType.resolve();
            if (resolved != null) {
                String fqn = resolved.getQualifiedName();
                if (JPA_REPOSITORY.equals(fqn) || CRUD_REPOSITORY.equals(fqn) || PAGING_REPOSITORY.equals(fqn)) {
                    PsiType[] typeArgs = superType.getParameters();
                    if (typeArgs.length > 0) return typeArgs[0].getCanonicalText();
                }
            }
        }
        return null;
    }

    private List<String> getJavaMethodSignatures(PsiClass psiClass) {
        List<String> sigs = new ArrayList<>();
        for (PsiMethod method : psiClass.getMethods()) {
            sigs.add(CallGraphExtractor.buildSignature(psiClass, method));
        }
        String repoName = psiClass.getQualifiedName();
        for (String stdMethod : STANDARD_REPO_METHODS) {
            sigs.add(repoName + "." + stdMethod + "():" + (stdMethod.startsWith("find") ? "List" : "void"));
        }
        return sigs;
    }

    private List<String> findJavaRepositoryCallers(PsiClass repoInterface, GlobalSearchScope scope) {
        List<String> callers = new ArrayList<>();
        ReferencesSearch.search(repoInterface, scope).forEach(ref -> {
            PsiElement element = ref.getElement();
            PsiMethod containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (containingMethod != null) {
                PsiClass containingClass = containingMethod.getContainingClass();
                if (containingClass != null) {
                    callers.add(CallGraphExtractor.buildSignature(containingClass, containingMethod));
                }
            }
        });
        return callers;
    }

    // ── Kotlin helpers ────────────────────────────────────────────────

    private boolean isKotlinInterface(KtClassOrObject ktClass) {
        return ktClass instanceof KtClass && ((KtClass) ktClass).isInterface();
    }

    private boolean isKotlinRepositoryInterface(KtClassOrObject ktClass) {
        for (KtSuperTypeListEntry superEntry : ktClass.getSuperTypeListEntries()) {
            String text = superEntry.getTypeReference() != null
                ? superEntry.getTypeReference().getText() : "";
            // Remove generic parameters for matching
            String baseName = text.replaceAll("<.*", "");
            if (JPA_REPOSITORY.endsWith("." + baseName)
                || baseName.equals("JpaRepository")
                || baseName.equals("CrudRepository")
                || baseName.equals("PagingAndSortingRepository")) {
                return true;
            }
        }
        return false;
    }

    private String extractKotlinDomainClassName(KtClassOrObject ktClass) {
        for (KtSuperTypeListEntry superEntry : ktClass.getSuperTypeListEntries()) {
            String text = superEntry.getTypeReference() != null
                ? superEntry.getTypeReference().getText() : "";
            // Look for JpaRepository<DomainClass, IdClass> pattern
            if (text.contains("Repository<") || text.contains("JpaRepository<")) {
                int start = text.indexOf('<');
                int end = text.indexOf(',');
                if (start >= 0 && end > start) {
                    return text.substring(start + 1, end).trim();
                }
                if (start >= 0 && text.indexOf('>') > start) {
                    return text.substring(start + 1, text.indexOf('>')).trim();
                }
            }
        }
        return null;
    }

    private List<String> getKotlinMethodSignatures(KtClassOrObject ktClass, String repoName) {
        List<String> sigs = new ArrayList<>();
        for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(ktClass)) {
            sigs.add(KotlinPsiHelper.buildKotlinSignature(ktClass, func));
        }
        for (String stdMethod : STANDARD_REPO_METHODS) {
            sigs.add(repoName + "." + stdMethod + "():" + (stdMethod.startsWith("find") ? "List" : "void"));
        }
        return sigs;
    }

    private List<String> findKotlinRepositoryCallers(KtClassOrObject repoInterface, GlobalSearchScope scope) {
        List<String> callers = new ArrayList<>();
        ReferencesSearch.search(repoInterface, scope).forEach(ref -> {
            PsiElement element = ref.getElement();
            // Try finding enclosing Kotlin function
            KtNamedFunction ktFunc = PsiTreeUtil.getParentOfType(element, KtNamedFunction.class);
            if (ktFunc != null) {
                KtClassOrObject ktClass = PsiTreeUtil.getParentOfType(ktFunc, KtClassOrObject.class);
                if (ktClass != null) {
                    callers.add(KotlinPsiHelper.buildKotlinSignature(ktClass, ktFunc));
                }
                return;
            }
            // Fallback: try Java method
            PsiMethod javaMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (javaMethod != null) {
                PsiClass javaClass = javaMethod.getContainingClass();
                if (javaClass != null) {
                    callers.add(CallGraphExtractor.buildSignature(javaClass, javaMethod));
                }
            }
        });
        return callers;
    }
}
