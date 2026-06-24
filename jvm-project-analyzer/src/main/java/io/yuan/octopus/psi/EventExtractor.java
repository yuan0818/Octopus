package io.yuan.octopus.psi;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;

import java.util.*;

public class EventExtractor {

    private static final String EVENT_LISTENER = "org.springframework.context.event.EventListener";
    private final Project project;

    public EventExtractor(Project project) { this.project = project; }

    public List<Map<String, Object>> extract() {
        List<Map<String, Object>> bindings = new ArrayList<>();
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);
            for (PsiClass psiClass : AllClassesSearch.search(scope, project).findAll()) {
                for (PsiMethod method : psiClass.getMethods()) {
                    PsiAnnotation ann = findAnnotation(method, EVENT_LISTENER);
                    if (ann == null) continue;
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("eventType", getEventType(ann));
                    b.put("publisherMethodSignatures", List.of());
                    b.put("listenerMethodSignatures",
                        List.of(CallGraphExtractor.buildSignature(psiClass, method)));
                    bindings.add(b);
                }
            }
        }
        return bindings;
    }

    private String getEventType(PsiAnnotation ann) {
        PsiAnnotationMemberValue v = ann.findDeclaredAttributeValue("value");
        if (v == null) v = ann.findDeclaredAttributeValue("classes");
        return v != null ? v.getText().replace("\"", "").trim() : "";
    }

    private PsiAnnotation findAnnotation(PsiMethod method, String fqn) {
        for (PsiAnnotation a : method.getAnnotations()) {
            if (fqn.equals(a.getQualifiedName())) return a;
        }
        return null;
    }
}
