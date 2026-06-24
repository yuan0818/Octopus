package io.yuan.octopus.psi;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

public class CallGraphExtractor {

    private static final Logger LOG = Logger.getInstance(CallGraphExtractor.class);
    private final Project project;
    private final SpringModelExtractor springExtractor;
    private final Map<String, String> resolvedImplementations = new HashMap<>();

    public CallGraphExtractor(Project project, SpringModelExtractor springExtractor) {
        this.project = project;
        this.springExtractor = springExtractor;
    }

    public CallGraphResult extract() {
        List<Map<String, Object>> methods = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);
            for (PsiClass psiClass : AllClassesSearch.search(scope, project).findAll()) {
                String qn = psiClass.getQualifiedName();
                if (qn == null) continue;
                for (PsiMethod method : psiClass.getMethods()) {
                    Map<String, Object> node = buildMethodNode(psiClass, method);
                    if (node != null) methods.add(node);
                    extractCalls(method, psiClass, scope, edges);
                }
            }
        }
        return new CallGraphResult(methods, edges);
    }

    private Map<String, Object> buildMethodNode(PsiClass psiClass, PsiMethod method) {
        String sig = buildSignature(psiClass, method);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("qualifiedClassName", psiClass.getQualifiedName());
        node.put("methodName", method.getName());
        node.put("signature", sig);
        node.put("parameterTypes", getParamTypes(method));
        node.put("returnType", method.getReturnType() != null ? method.getReturnType().getCanonicalText() : "void");
        node.put("modifiers", getModifiers(method));
        node.put("sourceFilePath", getSourcePath(psiClass));
        node.put("sourceCode", method.getBody() != null ? method.getBody().getText() : "");
        return node;
    }

    private void extractCalls(PsiMethod method, PsiClass psiClass, GlobalSearchScope scope,
                               List<Map<String, Object>> edges) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return;
        String callerSig = buildSignature(psiClass, method);
        String callerClass = psiClass.getQualifiedName();

        body.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expr) {
                super.visitMethodCallExpression(expr);
                PsiReferenceExpression ref = expr.getMethodExpression();
                String methodName = ref.getReferenceName();
                if (methodName == null) return;

                PsiMethod resolvedMethod = expr.resolveMethod();
                if (resolvedMethod == null) {
                    LOG.warn("resolveMethod() null for call: '" + expr.getText() + "' in " + callerClass + "." + method.getName());
                    return;
                }
                PsiClass targetClass = resolvedMethod.getContainingClass();
                if (targetClass == null || targetClass.getQualifiedName() == null) return;

                String calleeSig = buildSignature(targetClass, resolvedMethod);
                String callType = resolveCallType(expr);
                int line = getLineNumber(expr);

                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("callerSignature", callerSig);
                edge.put("callerClassName", callerClass);
                edge.put("callerMethodName", method.getName());
                edge.put("calleeSignature", calleeSig);
                edge.put("calleeClassName", targetClass.getQualifiedName());
                edge.put("calleeMethodName", resolvedMethod.getName());
                edge.put("callType", callType);
                edge.put("lineNumber", line);
                edges.add(edge);
            }
        });
    }

    public static String buildSignature(PsiClass psiClass, PsiMethod method) {
        String cn = psiClass.getQualifiedName();
        String mn = method.getName();
        StringBuilder params = new StringBuilder();
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if (!params.isEmpty()) params.append(", ");
            params.append(p.getType().getCanonicalText());
        }
        String rt = method.getReturnType() != null ? method.getReturnType().getCanonicalText() : "void";
        return cn + "." + mn + "(" + params + "):" + rt;
    }

    private List<String> getParamTypes(PsiMethod method) {
        List<String> types = new ArrayList<>();
        for (PsiParameter p : method.getParameterList().getParameters()) {
            types.add(p.getType().getCanonicalText());
        }
        return types;
    }

    private String getModifiers(PsiMethod method) {
        StringBuilder sb = new StringBuilder();
        if (method.hasModifierProperty(PsiModifier.PUBLIC)) sb.append("public ");
        if (method.hasModifierProperty(PsiModifier.PRIVATE)) sb.append("private ");
        if (method.hasModifierProperty(PsiModifier.PROTECTED)) sb.append("protected ");
        if (method.hasModifierProperty(PsiModifier.STATIC)) sb.append("static ");
        if (method.hasModifierProperty(PsiModifier.ABSTRACT)) sb.append("abstract ");
        return sb.toString().trim();
    }

    private String getSourcePath(PsiClass psiClass) {
        PsiFile file = psiClass.getContainingFile();
        if (file == null) return "";
        com.intellij.openapi.vfs.VirtualFile vf = file.getVirtualFile();
        return vf != null ? vf.getPath() : "";
    }

    private int getLineNumber(PsiElement element) {
        PsiDocumentManager docMgr = PsiDocumentManager.getInstance(project);
        com.intellij.openapi.editor.Document doc = docMgr.getDocument(element.getContainingFile());
        if (doc == null) return 0;
        return doc.getLineNumber(element.getTextOffset()) + 1;
    }

    private String resolveCallType(PsiMethodCallExpression expr) {
        PsiReferenceExpression ref = expr.getMethodExpression();
        PsiExpression qualifier = ref.getQualifierExpression();
        if (qualifier == null) return "THIS";
        if (qualifier instanceof PsiSuperExpression) return "SUPER";
        return "DIRECT";
    }

    public String resolveBeanImplementation(PsiType type) {
        if (type instanceof PsiClassType ct) {
            PsiClass resolved = ct.resolve();
            if (resolved != null && resolved.getQualifiedName() != null) {
                return resolved.getQualifiedName();
            }
        }
        return null;
    }

    public String findImplementingClass(String interfaceName) {
        if (resolvedImplementations.containsKey(interfaceName)) {
            return resolvedImplementations.get(interfaceName);
        }
        for (Map<String, Object> def : springExtractor.getBeanDefinitions()) {
            @SuppressWarnings("unchecked")
            List<String> ifaces = (List<String>) def.get("interfaceNames");
            if (ifaces != null && ifaces.contains(interfaceName)) {
                String impl = (String) def.get("beanClass");
                resolvedImplementations.put(interfaceName, impl);
                return impl;
            }
        }
        resolvedImplementations.put(interfaceName, null);
        return null;
    }

}
