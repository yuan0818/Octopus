package io.yuan.octopus.psi;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;

import java.util.*;

public class ScheduledExtractor {

    private static final String SCHEDULED_ANNOTATION = "org.springframework.scheduling.annotation.Scheduled";
    private final Project project;

    public ScheduledExtractor(Project project) { this.project = project; }

    public List<Map<String, Object>> extract() {
        List<Map<String, Object>> bindings = new ArrayList<>();
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);
            for (PsiClass psiClass : AllClassesSearch.search(scope, project).findAll()) {
                String qn = psiClass.getQualifiedName();
                if (qn == null) continue;
                for (PsiMethod method : psiClass.getMethods()) {
                    Map<String, Object> b = extractJavaScheduled(qn, method);
                    if (b != null) bindings.add(b);
                }
            }
        }
        return bindings;
    }

    private Map<String, Object> extractJavaScheduled(String qn, PsiMethod method) {
        PsiAnnotation ann = findAnnotation(method, SCHEDULED_ANNOTATION);
        if (ann == null) return null;
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("methodSignature", CallGraphExtractor.buildSignature(method.getContainingClass(), method));
        b.put("qualifiedClassName", qn);
        b.put("cronExpression", getValue(ann, "cron", ""));
        b.put("fixedDelay", getValue(ann, "fixedDelay", ""));
        b.put("fixedRate", getValue(ann, "fixedRate", ""));
        return b;
    }

    private String getValue(PsiAnnotation ann, String attr, String def) {
        PsiAnnotationMemberValue v = ann.findDeclaredAttributeValue(attr);
        if (v == null) return def;
        return v.getText().replace("\"", "").trim();
    }

    private PsiAnnotation findAnnotation(PsiMethod method, String fqn) {
        for (PsiAnnotation a : method.getAnnotations()) {
            if (fqn.equals(a.getQualifiedName())) return a;
        }
        return null;
    }
}
