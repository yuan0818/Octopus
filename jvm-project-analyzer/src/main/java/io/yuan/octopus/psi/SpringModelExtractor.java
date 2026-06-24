package io.yuan.octopus.psi;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;

import java.util.*;

@SuppressWarnings("deprecation")
public class SpringModelExtractor {

    private final Project project;
    private final List<Map<String, Object>> beanDefinitions = new ArrayList<>();
    private final Set<String> primaryBeans = new HashSet<>();
    private final Map<String, String> qualifiedBy = new LinkedHashMap<>();
    private final List<Map<String, Object>> constructorInjections = new ArrayList<>();
    private final List<Map<String, Object>> setterInjections = new ArrayList<>();
    private boolean extracted;

    public SpringModelExtractor(Project project) { this.project = project; }

    public Map<String, String> getBeanClassToNameMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map<String, Object> d : beanDefinitions) {
            String cn = (String) d.get("beanClassName");
            String bn = (String) d.get("beanName");
            if (cn != null && bn != null) m.put(cn, bn);
        }
        return m;
    }

    public List<Map<String, Object>> getBeanDefinitions() { return Collections.unmodifiableList(beanDefinitions); }
    public List<Map<String, Object>> getConstructorInjections() { return Collections.unmodifiableList(constructorInjections); }
    public List<Map<String, Object>> getSetterInjections() { return Collections.unmodifiableList(setterInjections); }
    public boolean isPrimary(String cn) { return primaryBeans.contains(cn); }
    public String getQualifier(String cn) { return qualifiedBy.get(cn); }

    public void extract() {
        if (extracted) return;
        extracted = true;
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            extractFromModule(module);
        }
    }

    private void extractFromModule(Module module) {
        GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);
        PsiClass[] allClasses = AllClassesSearch.search(scope, project).findAll().toArray(PsiClass[]::new);

        // Stereotype beans
        for (String ann : new String[]{
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController"
        }) {
            for (PsiClass cls : allClasses) {
                if (hasAnnotation(cls, ann)) addBeanDef(cls, ann);
            }
        }

        // @Bean methods
        for (PsiClass cls : allClasses) {
            if (isConfiguration(cls)) extractBeanMethods(cls);
        }

        // Constructor/setter injections
        for (PsiClass cls : allClasses) {
            if (!isBeanClass(cls)) continue;
            extractConstructorInjections(cls);
            extractSetterInjections(cls);
        }
    }

    private void addBeanDef(PsiClass cls, String annFqn) {
        String qn = cls.getQualifiedName();
        if (qn == null) return;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("beanClass", qn);
        d.put("beanClassName", qn);
        d.put("beanName", extractBeanName(cls, annFqn));
        d.put("scope", detectScope(cls));
        List<String> ifaces = new ArrayList<>();
        for (PsiClass i : cls.getInterfaces()) {
            if (i.getQualifiedName() != null) ifaces.add(i.getQualifiedName());
        }
        d.put("interfaceNames", ifaces);
        d.put("configClassOrMethod", qn);
        beanDefinitions.add(d);
        if (hasAnnotation(cls, "org.springframework.context.annotation.Primary")) primaryBeans.add(qn);
        String q = extractQualifier(cls);
        if (q != null) qualifiedBy.put(qn, q);
    }

    private void extractBeanMethods(PsiClass configClass) {
        for (PsiMethod method : configClass.getMethods()) {
            if (!hasMethodAnnotation(method, "org.springframework.context.annotation.Bean")) continue;
            String rt = method.getReturnType() != null ? method.getReturnType().getCanonicalText() : "void";
            if ("void".equals(rt)) continue;
            String qn = configClass.getQualifiedName() + "." + method.getName();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("beanClass", rt);
            d.put("beanClassName", rt);
            d.put("beanName", extractBeanMethodName(method));
            d.put("scope", detectScope(method));
            d.put("interfaceNames", List.of());
            d.put("configClassOrMethod", qn);
            beanDefinitions.add(d);
            if (hasMethodAnnotation(method, "org.springframework.context.annotation.Primary")) primaryBeans.add(rt);
            String q = extractQualifier(method);
            if (q != null) qualifiedBy.put(rt, q);
        }
    }

    private void extractConstructorInjections(PsiClass cls) {
        String cn = cls.getQualifiedName();
        if (cn == null) return;
        for (PsiMethod ctor : cls.getConstructors()) {
            if (!isAutowired(ctor) && cls.getConstructors().length != 1) continue;
            for (PsiParameter p : ctor.getParameterList().getParameters()) {
                String impl = resolveBean(p.getType(), cn);
                if (impl == null) continue;
                Map<String, Object> inj = new LinkedHashMap<>();
                inj.put("className", cn);
                inj.put("injectionType", "CONSTRUCTOR");
                inj.put("parameterType", p.getType().getCanonicalText());
                inj.put("targetBeanClass", impl);
                inj.put("qualifier", extractQualifier(p));
                constructorInjections.add(inj);
            }
        }
    }

    private void extractSetterInjections(PsiClass cls) {
        String cn = cls.getQualifiedName();
        if (cn == null) return;
        for (PsiMethod method : cls.getMethods()) {
            if (method.isConstructor() || !isAutowired(method)) continue;
            for (PsiParameter p : method.getParameterList().getParameters()) {
                String impl = resolveBean(p.getType(), cn);
                if (impl == null) continue;
                Map<String, Object> inj = new LinkedHashMap<>();
                inj.put("className", cn);
                inj.put("setterMethodName", method.getName());
                inj.put("injectionType", "SETTER");
                inj.put("parameterType", p.getType().getCanonicalText());
                inj.put("targetBeanClass", impl);
                inj.put("qualifier", extractQualifier(p));
                setterInjections.add(inj);
            }
        }
    }

    private boolean isConfiguration(PsiClass cls) {
        if (hasAnnotation(cls, "org.springframework.context.annotation.Configuration")) return true;
        if (hasAnnotation(cls, "org.springframework.boot.autoconfigure.SpringBootApplication")) return true;
        return false;
    }

    private boolean isBeanClass(PsiClass cls) {
        for (String ann : new String[]{
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController"
        }) {
            if (hasAnnotation(cls, ann)) return true;
        }
        return false;
    }

    private boolean isAutowired(PsiMethod method) {
        for (PsiAnnotation a : method.getAnnotations()) {
            String fqn = a.getQualifiedName();
            if (fqn != null && (fqn.contains("Autowired") || fqn.contains("Inject")
                || fqn.contains("Resource"))) return true;
        }
        return false;
    }

    private boolean hasAnnotation(PsiClass cls, String fqn) {
        for (PsiAnnotation a : cls.getAnnotations()) {
            if (fqn.equals(a.getQualifiedName())) return true;
        }
        return false;
    }

    private boolean hasMethodAnnotation(PsiMethod method, String fqn) {
        for (PsiAnnotation a : method.getAnnotations()) {
            if (fqn.equals(a.getQualifiedName())) return true;
        }
        return false;
    }

    private String extractBeanName(PsiClass cls, String annFqn) {
        PsiAnnotation ann = findAnnotation(cls, annFqn);
        if (ann != null) {
            String v = getAttr(ann, "value");
            if (v != null && !v.isEmpty()) return v;
        }
        return deriveName(cls.getName());
    }

    private String extractBeanMethodName(PsiMethod method) {
        PsiAnnotation ann = findAnnotation(method, "org.springframework.context.annotation.Bean");
        if (ann != null) {
            String v = getAttr(ann, "value");
            if (v != null && !v.isEmpty()) return v;
            v = getAttr(ann, "name");
            if (v != null && !v.isEmpty()) return v;
        }
        return method.getName();
    }

    private String detectScope(PsiModifierListOwner element) {
        for (PsiAnnotation a : element.getAnnotations()) {
            if ("org.springframework.context.annotation.Scope".equals(a.getQualifiedName())) {
                PsiAnnotationMemberValue v = a.findDeclaredAttributeValue("value");
                if (v != null) return v.getText().replace("\"", "");
            }
        }
        return "singleton";
    }

    private String extractQualifier(PsiModifierListOwner element) {
        for (PsiAnnotation a : element.getAnnotations()) {
            if ("org.springframework.beans.factory.annotation.Qualifier".equals(a.getQualifiedName())) {
                PsiAnnotationMemberValue v = a.findDeclaredAttributeValue("value");
                if (v != null) return v.getText().replace("\"", "");
            }
        }
        return null;
    }

    private String getAttr(PsiAnnotation ann, String attr) {
        PsiAnnotationMemberValue v = ann.findDeclaredAttributeValue(attr);
        return v != null ? v.getText().replace("\"", "") : null;
    }

    private PsiAnnotation findAnnotation(PsiModifierListOwner owner, String fqn) {
        for (PsiAnnotation a : owner.getAnnotations()) {
            if (fqn.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

    private String deriveName(String name) {
        if (name == null || name.isEmpty()) return "";
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private String resolveBean(PsiType type, String contextClass) {
        if (type instanceof PsiClassType ct) {
            PsiClass resolved = ct.resolve();
            if (resolved != null && resolved.getQualifiedName() != null) {
                return resolved.getQualifiedName();
            }
        }
        return null;
    }
}
