package io.yuan.octopus.psi;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import org.jetbrains.kotlin.psi.*;

import java.util.*;

public class SpringModelExtractor {

    private final Project project;
    private final List<Map<String, Object>> beanDefinitions = new ArrayList<>();
    private final Set<String> primaryBeans = new HashSet<>();
    private final Map<String, String> qualifiedBy = new LinkedHashMap<>();
    private final List<Map<String, Object>> constructorInjections = new ArrayList<>();
    private final List<Map<String, Object>> setterInjections = new ArrayList<>();
    private boolean extracted;

    private static final String[] STEREOTYPE_ANNOTATIONS = {
        "org.springframework.stereotype.Component",
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Repository",
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController"
    };

    private static final String[] AUTOWIRED_ANNOTATIONS = {
        "org.springframework.beans.factory.annotation.Autowired",
        "jakarta.annotation.Resource",
        "javax.annotation.Resource",
        "jakarta.inject.Inject",
        "javax.inject.Inject"
    };

    private static final String CONFIGURATION_ANNOTATION = "org.springframework.context.annotation.Configuration";
    private static final String BEAN_ANNOTATION = "org.springframework.context.annotation.Bean";
    private static final String PRIMARY_ANNOTATION = "org.springframework.context.annotation.Primary";
    private static final String QUALIFIER_ANNOTATION = "org.springframework.beans.factory.annotation.Qualifier";

    public SpringModelExtractor(Project project) {
        this.project = project;
    }

    public Map<String, String> getBeanClassToNameMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map<String, Object> def : beanDefinitions) {
            String className = (String) def.get("beanClassName");
            String beanName = (String) def.get("beanName");
            if (className != null && beanName != null) {
                map.put(className, beanName);
            }
        }
        return map;
    }

    public List<Map<String, Object>> getBeanDefinitions() {
        return Collections.unmodifiableList(beanDefinitions);
    }

    public List<Map<String, Object>> getConstructorInjections() {
        return Collections.unmodifiableList(constructorInjections);
    }

    public List<Map<String, Object>> getSetterInjections() {
        return Collections.unmodifiableList(setterInjections);
    }

    public boolean isPrimary(String beanClassName) {
        return primaryBeans.contains(beanClassName);
    }

    public String getQualifier(String beanClassName) {
        return qualifiedBy.get(beanClassName);
    }

    public void extract() {
        if (extracted) {
            return;
        }
        extracted = true;
        Module[] modules = ReadAction.compute(() -> ModuleManager.getInstance(project).getModules());
        for (Module module : modules) {
            extractFromModule(module);
        }
    }

    private void extractFromModule(Module module) {
        GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

        PsiClass[] allClasses = ReadAction.compute(() ->
            AllClassesSearch.search(scope, project)
                .findAll()
                .toArray(PsiClass[]::new));

        List<KtFile> ktFiles = ReadAction.compute(() ->
            KotlinPsiHelper.findKotlinFiles(project, scope));

        // ── Java stereotype beans ──
        for (String annotationFqn : STEREOTYPE_ANNOTATIONS) {
            for (PsiClass psiClass : allClasses) {
                if (!ReadAction.compute(() -> hasJavaAnnotation(psiClass, annotationFqn))) continue;
                ReadAction.run(() -> addJavaBeanDefinition(psiClass, annotationFqn));
            }
        }

        // ── Kotlin stereotype beans ──
        for (KtFile ktFile : ktFiles) {
            for (KtClassOrObject ktClass : ReadAction.compute(() -> KotlinPsiHelper.getKotlinClasses(ktFile))) {
                for (String annotationFqn : STEREOTYPE_ANNOTATIONS) {
                    if (ReadAction.compute(() -> KotlinPsiHelper.hasAnnotation(ktClass, annotationFqn))) {
                        ReadAction.run(() -> addKotlinBeanDefinition(ktClass, annotationFqn));
                        break;
                    }
                }
            }
        }

        // ── Java @Bean methods ──
        for (PsiClass psiClass : allClasses) {
            if (ReadAction.compute(() -> isJavaConfiguration(psiClass))) {
                ReadAction.run(() -> extractJavaBeanMethods(psiClass));
            }
        }

        // ── Kotlin @Bean methods ──
        for (KtFile ktFile : ktFiles) {
            for (KtClassOrObject ktClass : ReadAction.compute(() -> KotlinPsiHelper.getKotlinClasses(ktFile))) {
                if (ReadAction.compute(() -> isKotlinConfiguration(ktClass))) {
                    ReadAction.run(() -> extractKotlinBeanMethods(ktClass));
                }
            }
        }

        // ── Java constructor/setter injections ──
        for (PsiClass psiClass : allClasses) {
            if (!ReadAction.compute(() -> isJavaBeanClass(psiClass))) continue;
            ReadAction.run(() -> extractJavaConstructorInjections(psiClass));
            ReadAction.run(() -> extractJavaSetterInjections(psiClass));
        }

        // ── Kotlin constructor/setter injections ──
        for (KtFile ktFile : ktFiles) {
            for (KtClassOrObject ktClass : ReadAction.compute(() -> KotlinPsiHelper.getKotlinClasses(ktFile))) {
                if (!ReadAction.compute(() -> isKotlinBeanClass(ktClass))) continue;
                ReadAction.run(() -> extractKotlinConstructorInjections(ktClass));
            }
        }
    }

    // ── Bean definition helpers ──────────────────────────────────────

    private void addJavaBeanDefinition(PsiClass psiClass, String annotationFqn) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) return;

        String beanName = extractBeanName(psiClass, annotationFqn);

        Map<String, Object> beanDef = new LinkedHashMap<>();
        beanDef.put("beanClass", qualifiedName);
        beanDef.put("beanClassName", qualifiedName);
        beanDef.put("beanName", beanName);
        beanDef.put("scope", detectScope(psiClass));

        List<String> interfaces = new ArrayList<>();
        for (PsiClass iface : psiClass.getInterfaces()) {
            String ifaceName = iface.getQualifiedName();
            if (ifaceName != null) interfaces.add(ifaceName);
        }
        beanDef.put("interfaceNames", interfaces);
        beanDef.put("configClassOrMethod", qualifiedName);

        beanDefinitions.add(beanDef);

        if (hasJavaAnnotation(psiClass, PRIMARY_ANNOTATION)) {
            primaryBeans.add(qualifiedName);
        }
        String qualifier = extractQualifierValueJava(psiClass);
        if (qualifier != null) {
            qualifiedBy.put(qualifiedName, qualifier);
        }
    }

    private void addKotlinBeanDefinition(KtClassOrObject ktClass, String annotationFqn) {
        String qualifiedName = KotlinPsiHelper.getKotlinClassFqn(ktClass);
        String beanName = extractKotlinBeanName(ktClass, annotationFqn);

        Map<String, Object> beanDef = new LinkedHashMap<>();
        beanDef.put("beanClass", qualifiedName);
        beanDef.put("beanClassName", qualifiedName);
        beanDef.put("beanName", beanName);
        beanDef.put("scope", detectKotlinScope(ktClass));

        List<String> interfaces = new ArrayList<>();
        for (KtSuperTypeListEntry superEntry : ktClass.getSuperTypeListEntries()) {
            if (superEntry instanceof KtSuperTypeEntry) {
                String text = superEntry.getTypeReference() != null
                    ? superEntry.getTypeReference().getText() : null;
                if (text != null) interfaces.add(text);
            }
        }
        beanDef.put("interfaceNames", interfaces);
        beanDef.put("configClassOrMethod", qualifiedName);

        beanDefinitions.add(beanDef);

        if (KotlinPsiHelper.hasAnnotation(ktClass, PRIMARY_ANNOTATION)) {
            primaryBeans.add(qualifiedName);
        }
    }

    // ── @Bean method extraction ──────────────────────────────────────

    private void extractJavaBeanMethods(PsiClass configClass) {
        for (PsiMethod method : configClass.getMethods()) {
            if (!hasJavaMethodAnnotation(method, BEAN_ANNOTATION)) continue;

            String returnType = method.getReturnType() != null
                ? method.getReturnType().getCanonicalText() : "void";
            if ("void".equals(returnType)) continue;

            String beanName = extractJavaBeanMethodName(method);
            String qualifiedName = configClass.getQualifiedName() + "." + method.getName();

            Map<String, Object> beanDef = new LinkedHashMap<>();
            beanDef.put("beanClass", returnType);
            beanDef.put("beanClassName", returnType);
            beanDef.put("beanName", beanName);
            beanDef.put("scope", detectScope(method));
            beanDef.put("interfaceNames", List.of());
            beanDef.put("configClassOrMethod", qualifiedName);

            beanDefinitions.add(beanDef);

            if (hasJavaMethodAnnotation(method, PRIMARY_ANNOTATION)) {
                primaryBeans.add(returnType);
            }
            String qualifier = extractQualifierValueJava(method);
            if (qualifier != null) {
                qualifiedBy.put(returnType, qualifier);
            }
        }
    }

    private void extractKotlinBeanMethods(KtClassOrObject configClass) {
        for (KtNamedFunction func : KotlinPsiHelper.getKotlinFunctions(configClass)) {
            if (!KotlinPsiHelper.hasAnnotation(func, BEAN_ANNOTATION)) continue;

            String returnType = func.getTypeReference() != null
                ? func.getTypeReference().getText() : "kotlin.Unit";
            if ("kotlin.Unit".equals(returnType)) continue;

            String beanName = extractKotlinBeanMethodName(func);
            String qualifiedName = KotlinPsiHelper.getKotlinClassFqn(configClass) + "." + func.getName();

            Map<String, Object> beanDef = new LinkedHashMap<>();
            beanDef.put("beanClass", returnType);
            beanDef.put("beanClassName", returnType);
            beanDef.put("beanName", beanName);
            beanDef.put("scope", detectKotlinScope(func));
            beanDef.put("interfaceNames", List.of());
            beanDef.put("configClassOrMethod", qualifiedName);

            beanDefinitions.add(beanDef);

            if (KotlinPsiHelper.hasAnnotation(func, PRIMARY_ANNOTATION)) {
                primaryBeans.add(returnType);
            }
        }
    }

    // ── Constructor injection extraction ─────────────────────────────

    private void extractJavaConstructorInjections(PsiClass psiClass) {
        String className = psiClass.getQualifiedName();
        if (className == null) return;

        for (PsiMethod ctor : psiClass.getConstructors()) {
            if (!isConstructorAutowired(ctor) && psiClass.getConstructors().length != 1) continue;

            PsiParameter[] params = ctor.getParameterList().getParameters();
            if (params.length == 0) continue;

            for (PsiParameter param : params) {
                String implClass = resolveBeanImplementation(param.getType());
                if (implClass == null) continue;

                Map<String, Object> injection = new LinkedHashMap<>();
                injection.put("className", className);
                injection.put("injectionType", "CONSTRUCTOR");
                injection.put("parameterType", param.getType().getCanonicalText());
                injection.put("targetBeanClass", implClass);
                injection.put("qualifier", extractQualifierValueJava(param));
                constructorInjections.add(injection);
            }
        }
    }

    private void extractJavaSetterInjections(PsiClass psiClass) {
        String className = psiClass.getQualifiedName();
        if (className == null) return;

        for (PsiMethod method : psiClass.getMethods()) {
            if (!isAutowiredMethod(method)) continue;

            PsiParameter[] params = method.getParameterList().getParameters();
            if (params.length == 0) continue;

            for (PsiParameter param : params) {
                String implClass = resolveBeanImplementation(param.getType());
                if (implClass == null) continue;

                Map<String, Object> injection = new LinkedHashMap<>();
                injection.put("className", className);
                injection.put("setterMethodName", method.getName());
                injection.put("injectionType", "SETTER");
                injection.put("parameterType", param.getType().getCanonicalText());
                injection.put("targetBeanClass", implClass);
                injection.put("qualifier", extractQualifierValueJava(param));
                setterInjections.add(injection);
            }
        }
    }

    private void extractKotlinConstructorInjections(KtClassOrObject ktClass) {
        String className = KotlinPsiHelper.getKotlinClassFqn(ktClass);

        // ── Primary constructor ──
        KtPrimaryConstructor primaryCtor = ktClass.getPrimaryConstructor();
        if (primaryCtor != null) {
            // Single-constructor beans are autowired implicitly; multi-constructor beans need @Autowired
            boolean hasAutowired = isAnnotatedAutowiredKotlin(primaryCtor);
            boolean isSingleCtor = ktClass.getSecondaryConstructors().isEmpty();
            if (hasAutowired || isSingleCtor) {
                for (KtParameter param : primaryCtor.getValueParameters()) {
                    String typeText = param.getTypeReference() != null
                        ? param.getTypeReference().getText() : null;
                    if (typeText == null) continue;
                    if (typeText.endsWith("?")) typeText = typeText.substring(0, typeText.length() - 1);

                    String implClass = resolveBeanImplementationByFqn(typeText);
                    if (implClass == null) continue;

                    Map<String, Object> injection = new LinkedHashMap<>();
                    injection.put("className", className);
                    injection.put("injectionType", "CONSTRUCTOR");
                    injection.put("parameterType", typeText);
                    injection.put("targetBeanClass", implClass);
                    constructorInjections.add(injection);
                }
            }
        }

        // ── Secondary constructors ──
        for (KtSecondaryConstructor secondaryCtor : ktClass.getSecondaryConstructors()) {
            if (!isAnnotatedAutowiredKotlin(secondaryCtor)) continue;

            for (KtParameter param : secondaryCtor.getValueParameters()) {
                String typeText = param.getTypeReference() != null
                    ? param.getTypeReference().getText() : null;
                if (typeText == null) continue;
                if (typeText.endsWith("?")) typeText = typeText.substring(0, typeText.length() - 1);

                String implClass = resolveBeanImplementationByFqn(typeText);
                if (implClass == null) continue;

                Map<String, Object> injection = new LinkedHashMap<>();
                injection.put("className", className);
                injection.put("injectionType", "CONSTRUCTOR");
                injection.put("parameterType", typeText);
                injection.put("targetBeanClass", implClass);
                constructorInjections.add(injection);
            }
        }
    }

    private boolean isAnnotatedAutowiredKotlin(KtAnnotated annotated) {
        for (KtAnnotationEntry entry : annotated.getAnnotationEntries()) {
            String fqn = KotlinPsiHelper.getAnnotationFqn(entry);
            if (fqn == null) continue;
            for (String autowiredFqn : AUTOWIRED_ANNOTATIONS) {
                if (autowiredFqn.equals(fqn)) return true;
            }
        }
        return false;
    }

    // ── Autowired detection ──────────────────────────────────────────

    private boolean isConstructorAutowired(PsiMethod ctor) {
        if (ctor.getAnnotations().length > 0 && hasAnyAutowiredAnnotation(ctor)) {
            return true;
        }
        return false;
    }

    private boolean isAutowiredMethod(PsiMethod method) {
        if (method.isConstructor()) return false;
        return hasAnyAutowiredAnnotation(method);
    }

    private boolean hasAnyAutowiredAnnotation(PsiMethod method) {
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (fqn == null) continue;
            for (String autowiredFqn : AUTOWIRED_ANNOTATIONS) {
                if (autowiredFqn.equals(fqn)) return true;
            }
        }
        return false;
    }

    // ── Configuration detection ──────────────────────────────────────

    private boolean isJavaConfiguration(PsiClass psiClass) {
        if (hasJavaAnnotation(psiClass, CONFIGURATION_ANNOTATION)) return true;
        if (hasJavaAnnotation(psiClass, "org.springframework.boot.autoconfigure.SpringBootApplication")) return true;
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (fqn != null && fqn.endsWith("Configuration")) return true;
        }
        return false;
    }

    private boolean isKotlinConfiguration(KtClassOrObject ktClass) {
        if (KotlinPsiHelper.hasAnnotation(ktClass, CONFIGURATION_ANNOTATION)) return true;
        if (KotlinPsiHelper.hasAnnotation(ktClass, "org.springframework.boot.autoconfigure.SpringBootApplication"))
            return true;
        return false;
    }

    private boolean isJavaBeanClass(PsiClass psiClass) {
        for (String ann : STEREOTYPE_ANNOTATIONS) {
            if (hasJavaAnnotation(psiClass, ann)) return true;
        }
        return false;
    }

    private boolean isKotlinBeanClass(KtClassOrObject ktClass) {
        for (String ann : STEREOTYPE_ANNOTATIONS) {
            if (KotlinPsiHelper.hasAnnotation(ktClass, ann)) return true;
        }
        return false;
    }

    // ── Bean name derivation ─────────────────────────────────────────

    private String extractBeanName(PsiClass psiClass, String annotationFqn) {
        PsiAnnotation annotation = null;
        for (PsiAnnotation ann : psiClass.getAnnotations()) {
            if (annotationFqn.equals(ann.getQualifiedName())) {
                annotation = ann;
                break;
            }
        }
        if (annotation != null) {
            String value = extractAnnotationAttribute(annotation, "value");
            if (value != null && !value.isEmpty()) return value;
        }
        return deriveBeanName(psiClass.getName());
    }

    private String extractKotlinBeanName(KtClassOrObject ktClass, String annotationFqn) {
        for (KtAnnotationEntry entry : ktClass.getAnnotationEntries()) {
            if (annotationFqn.equals(KotlinPsiHelper.getAnnotationFqn(entry))) {
                String value = extractKotlinAnnotationAttribute(entry, "value");
                if (value != null && !value.isEmpty()) return value;
            }
        }
        return deriveBeanName(ktClass.getName());
    }

    private String extractJavaBeanMethodName(PsiMethod method) {
        PsiAnnotation annotation = null;
        for (PsiAnnotation ann : method.getAnnotations()) {
            if (BEAN_ANNOTATION.equals(ann.getQualifiedName())) {
                annotation = ann;
                break;
            }
        }
        if (annotation != null) {
            String value = extractAnnotationAttribute(annotation, "value");
            if (value != null && !value.isEmpty() && !"".equals(value)) return value;
            String name = extractAnnotationAttribute(annotation, "name");
            if (name != null && !name.isEmpty()) return name;
        }
        return method.getName();
    }

    private String extractKotlinBeanMethodName(KtNamedFunction func) {
        for (KtAnnotationEntry entry : func.getAnnotationEntries()) {
            if (BEAN_ANNOTATION.equals(KotlinPsiHelper.getAnnotationFqn(entry))) {
                String value = extractKotlinAnnotationAttribute(entry, "value");
                if (value != null && !value.isEmpty()) return value;
                String name = extractKotlinAnnotationAttribute(entry, "name");
                if (name != null && !name.isEmpty()) return name;
            }
        }
        return func.getName();
    }

    // ── Scope detection ──────────────────────────────────────────────

    private String detectScope(PsiModifierListOwner element) {
        for (PsiAnnotation annotation : element.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if ("org.springframework.context.annotation.Scope".equals(fqn)) {
                PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("value");
                if (value != null) return value.getText().replace("\"", "");
            }
        }
        return "singleton";
    }

    private String detectKotlinScope(KtAnnotated element) {
        for (KtAnnotationEntry entry : element.getAnnotationEntries()) {
            if ("org.springframework.context.annotation.Scope".equals(
                    KotlinPsiHelper.getAnnotationFqn(entry))) {
                for (ValueArgument va : entry.getValueArguments()) {
                    if ("value".equals(va.getArgumentName() != null
                            ? va.getArgumentName().getAsName().asString() : "value")) {
                        KtExpression expr = va.getArgumentExpression();
                        if (expr != null) return expr.getText().replace("\"", "");
                    }
                }
            }
        }
        return "singleton";
    }

    // ── Qualifier / Primary extraction ───────────────────────────────

    private String extractQualifierValueJava(PsiModifierListOwner element) {
        for (PsiAnnotation annotation : element.getAnnotations()) {
            if (QUALIFIER_ANNOTATION.equals(annotation.getQualifiedName())) {
                PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("value");
                if (value != null) return value.getText().replace("\"", "");
            }
        }
        return null;
    }

    // ── Annotation helpers ────────────────────────────────────────────

    private String extractAnnotationAttribute(PsiAnnotation annotation, String attrName) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attrName);
        if (value == null) return null;
        return value.getText().replace("\"", "");
    }

    private String extractKotlinAnnotationAttribute(KtAnnotationEntry entry, String attrName) {
        for (ValueArgument va : entry.getValueArguments()) {
            String name = va.getArgumentName() != null
                ? va.getArgumentName().getAsName().asString() : "value";
            if (attrName.equals(name)) {
                KtExpression expr = va.getArgumentExpression();
                if (expr != null) return expr.getText().replace("\"", "");
            }
        }
        return null;
    }

    private boolean hasJavaAnnotation(PsiClass psiClass, String annotationFqn) {
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (annotationFqn.equals(fqn)) return true;
        }
        return false;
    }

    private boolean hasJavaMethodAnnotation(PsiMethod method, String annotationFqn) {
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (annotationFqn.equals(fqn)) return true;
        }
        return false;
    }

    private String deriveBeanName(String simpleName) {
        if (simpleName == null) return "";
        if (simpleName.length() == 1) return simpleName.toLowerCase(Locale.ROOT);
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    // ── Bean resolution ──────────────────────────────────────────────

    public String resolveBeanImplementation(PsiType type) {
        if (type instanceof PsiClassType classType) {
            PsiClass resolvedClass = classType.resolve();
            if (resolvedClass != null) {
                String qualifiedName = resolvedClass.getQualifiedName();
                if (qualifiedName != null) {
                    if (resolvedClass.isInterface()) {
                        return findBeanImplementingInterface(qualifiedName);
                    }
                    return qualifiedName;
                }
            }
        }
        return null;
    }

    public String resolveBeanImplementationByFqn(String fqn) {
        for (Map<String, Object> def : beanDefinitions) {
            if (fqn.equals(def.get("beanClass"))) {
                return fqn;
            }
        }
        return findBeanImplementingInterface(fqn);
    }

    private String findBeanImplementingInterface(String interfaceName) {
        List<String> candidates = new ArrayList<>();
        for (Map<String, Object> def : beanDefinitions) {
            @SuppressWarnings("unchecked")
            List<String> interfaces = (List<String>) def.get("interfaceNames");
            if (interfaces != null && interfaces.contains(interfaceName)) {
                candidates.add((String) def.get("beanClass"));
            }
        }

        if (candidates.size() == 1) return candidates.get(0);
        if (candidates.isEmpty()) return null;

        for (String candidate : candidates) {
            if (primaryBeans.contains(candidate)) return candidate;
        }

        return candidates.get(0);
    }
}
