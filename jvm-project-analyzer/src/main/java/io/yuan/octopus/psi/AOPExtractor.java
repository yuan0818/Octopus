package io.yuan.octopus.psi;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;

import java.util.*;

public class AOPExtractor {

    private static final Logger LOG = Logger.getInstance(AOPExtractor.class);
    private final Project project;

    public AOPExtractor(Project project) { this.project = project; }

    public List<Map<String, Object>> extract() {
        List<Map<String, Object>> bindings = new ArrayList<>();
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);
            for (PsiClass psiClass : AllClassesSearch.search(scope, project).findAll()) {
                String qn = psiClass.getQualifiedName();
                if (qn == null) continue;
                // Check if class has @Aspect annotation or any advice method
                for (PsiMethod method : psiClass.getMethods()) {
                    String adviceType = getAdviceType(method);
                    if (adviceType == null) continue;
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("aspectClassName", qn);
                    b.put("aspectMethodName", method.getName());
                    b.put("adviceType", adviceType);
                    b.put("pointcutExpression", getPointcutExpr(method));
                    b.put("matchedMethodSignatures", List.of());
                    bindings.add(b);
                }
            }
        }
        return bindings;
    }

    private String getAdviceType(PsiMethod method) {
        for (PsiAnnotation ann : method.getAnnotations()) {
            String fqn = ann.getQualifiedName();
            if (fqn == null) continue;
            if (fqn.contains("Before")) return "BEFORE";
            if (fqn.contains("AfterReturning")) return "AFTER_RETURNING";
            if (fqn.contains("AfterThrowing")) return "AFTER_THROWING";
            if (fqn.contains("After")) return "AFTER";
            if (fqn.contains("Around")) return "AROUND";
        }
        return null;
    }

    private String getPointcutExpr(PsiMethod method) {
        for (PsiAnnotation ann : method.getAnnotations()) {
            PsiAnnotationMemberValue v = ann.findDeclaredAttributeValue("value");
            if (v == null) v = ann.findDeclaredAttributeValue("pointcut");
            if (v != null) return v.getText().replace("\"", "").trim();
        }
        return "";
    }
}
