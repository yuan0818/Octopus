package io.yuan.octopus.psi;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import org.jetbrains.kotlin.psi.*;

import java.util.*;

public class ScheduledExtractor {

    private static final String SCHEDULED_ANNOTATION = "org.springframework.scheduling.annotation.Scheduled";

    private final Project project;

    public ScheduledExtractor(Project project) {
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
                String qualifiedClassName = psiClass.getQualifiedName();
                if (qualifiedClassName == null) continue;

                for (PsiMethod method : psiClass.getMethods()) {
                    Map<String, Object> binding = extractJavaScheduled(qualifiedClassName, method);
                    if (binding != null) bindings.add(binding);
                }
            }

            List<KtFile> ktFiles = KotlinPsiHelper.findKotlinFiles(project, scope);
            for (KtFile ktFile : ktFiles) {
                for (KtClassOrObject ktClass : KotlinPsiHelper.getKotlinClasses(ktFile)) {
                    String qualifiedClassName = KotlinPsiHelper.getKotlinClassFqn(ktClass);

                    for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(ktClass)) {
                        Map<String, Object> binding = extractKotlinScheduled(qualifiedClassName, func);
                        if (binding != null) bindings.add(binding);
                    }
                }
            }
        }

        return bindings;
    }

    private Map<String, Object> extractJavaScheduled(String qualifiedClassName, PsiMethod method) {
        PsiAnnotation annotation = findAnnotation(method, SCHEDULED_ANNOTATION);
        if (annotation == null) return null;

        String methodSignature = CallGraphExtractor.buildSignature(
                method.getContainingClass(), method);

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("methodSignature", methodSignature);
        binding.put("qualifiedClassName", qualifiedClassName);
        binding.put("cronExpression", extractAnnotationValue(annotation, "cron", ""));
        binding.put("fixedDelay", extractAnnotationValue(annotation, "fixedDelay", ""));
        binding.put("fixedRate", extractAnnotationValue(annotation, "fixedRate", ""));
        return binding;
    }

    private Map<String, Object> extractKotlinScheduled(String qualifiedClassName, KtNamedFunction func) {
        KtAnnotationEntry entry = findKotlinAnnotation(func, SCHEDULED_ANNOTATION);
        if (entry == null) return null;

        String methodSignature = KotlinPsiHelper.buildKotlinSignature(
                (KtClassOrObject) func.getParent(), func);

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("methodSignature", methodSignature);
        binding.put("qualifiedClassName", qualifiedClassName);
        binding.put("cronExpression", extractKotlinAnnotationValue(entry, "cron", ""));
        binding.put("fixedDelay", extractKotlinAnnotationValue(entry, "fixedDelay", ""));
        binding.put("fixedRate", extractKotlinAnnotationValue(entry, "fixedRate", ""));
        return binding;
    }

    private String extractAnnotationValue(PsiAnnotation annotation, String attribute, String defaultValue) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value == null) return defaultValue;
        String text = value.getText();
        return text.replace("\"", "").trim();
    }

    private String extractKotlinAnnotationValue(KtAnnotationEntry entry, String attribute, String defaultValue) {
        for (ValueArgument va : entry.getValueArguments()) {
            String name = va.getArgumentName() != null
                    ? va.getArgumentName().getAsName().asString() : "value";
            if (name.equals(attribute)) {
                KtExpression expr = va.getArgumentExpression();
                if (expr != null) return expr.getText().replace("\"", "").trim();
            }
        }
        return defaultValue;
    }

    private PsiAnnotation findAnnotation(PsiMethod method, String fqn) {
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (fqn.equals(qualifiedName)) return annotation;
        }
        return null;
    }

    private KtAnnotationEntry findKotlinAnnotation(KtNamedFunction func, String fqn) {
        for (KtAnnotationEntry entry : func.getAnnotationEntries()) {
            String resolved = KotlinPsiHelper.getAnnotationFqn(entry);
            if (fqn.equals(resolved)) return entry;
        }
        return null;
    }
}
