package io.yuan.octopus.psi;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Kotlin PSI support — all methods use reflection so the class loads
 * even when the Kotlin plugin is not available at runtime.
 * Currently NOT used by the extractors (they analyze Java source only).
 */
public class KotlinPsiHelper {

    private static final Logger log = LoggerFactory.getLogger(KotlinPsiHelper.class);
    private static final boolean KOTLIN_AVAILABLE;

    static {
        boolean available = false;
        try {
            Class.forName("org.jetbrains.kotlin.psi.KtFile", false, KotlinPsiHelper.class.getClassLoader());
            available = true;
        } catch (Throwable e) {
            // Kotlin PSI not available — analysis will use JavaParser
        }
        KOTLIN_AVAILABLE = available;
        if (!KOTLIN_AVAILABLE) {
            log.info("Kotlin PSI not available — analysis uses JavaParser");
        }
    }

    private KotlinPsiHelper() {}

    public static boolean isAvailable() { return KOTLIN_AVAILABLE; }

    @SuppressWarnings("unchecked")
    public static <T> List<T> findKotlinFiles(Project project, GlobalSearchScope scope) {
        if (!KOTLIN_AVAILABLE) return List.of();
        try {
            Collection<VirtualFile> files = FilenameIndex.getAllFilesByExt(project, "kt", scope);
            List<T> result = new ArrayList<>();
            for (VirtualFile vf : files) {
                PsiFile pf = PsiManager.getInstance(project).findFile(vf);
                if (pf != null) result.add((T) pf);
            }
            return result;
        } catch (Exception e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> getKotlinClasses(Object ktFile) {
        if (!KOTLIN_AVAILABLE) return List.of();
        try { return invokeList(ktFile, "getDeclarations"); }
        catch (Exception e) { return List.of(); }
    }

    public static boolean hasAnnotation(Object ktClass, String annotationFqn) {
        if (!KOTLIN_AVAILABLE) return false;
        try {
            List<Object> entries = invokeList(ktClass, "getAnnotationEntries");
            if (entries == null) return false;
            return entries.stream().anyMatch(e -> annotationFqn.equals(getAnnotationFqn(e)));
        } catch (Exception e) { return false; }
    }

    public static String getKotlinClassFqn(Object ktClass) {
        if (!KOTLIN_AVAILABLE) return "";
        try {
            Object ktFile = invoke(ktClass, "getContainingKtFile");
            String pkg = invoke(ktFile, "getPackageName");
            String name = invoke(ktClass, "getName");
            return name != null ? (pkg != null && !pkg.isEmpty() ? pkg + "." : "") + name : "";
        } catch (Exception e) { return ""; }
    }

    public static String getAnnotationFqn(Object entry) {
        if (!KOTLIN_AVAILABLE) return "";
        try {
            Object typeRef = invoke(entry, "getTypeReference");
            if (typeRef != null) {
                Object type = invoke(typeRef, "getType");
                return type != null ? type.toString() : "";
            }
        } catch (Exception ignored) {}
        return "";
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> getKotlinFunctions(Object ktClass) {
        if (!KOTLIN_AVAILABLE) return List.of();
        try { return invokeList(ktClass, "getDeclarations"); }
        catch (Exception e) { return List.of(); }
    }

    public static String buildKotlinSignature(Object ktClass, Object ktFunc) {
        String cn = getKotlinClassFqn(ktClass);
        String mn = invoke(ktFunc, "getName");
        return mn != null ? cn + "." + mn : cn;
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> getKotlinProperties(Object ktClass) {
        if (!KOTLIN_AVAILABLE) return List.of();
        try { return invokeList(ktClass, "getDeclarations"); }
        catch (Exception e) { return List.of(); }
    }

    public static String getCallName(Object callExpr) {
        if (!KOTLIN_AVAILABLE) return "";
        try {
            Object callee = invoke(callExpr, "getCalleeExpression");
            return callee != null ? invoke(callee, "getText") : "";
        } catch (Exception e) { return ""; }
    }

    public static String getReceiverText(Object callExpr) {
        if (!KOTLIN_AVAILABLE) return "";
        try {
            Object receiver = invoke(callExpr, "getReceiverExpression");
            return receiver != null ? invoke(receiver, "getText") : "";
        } catch (Exception e) { return ""; }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object obj, String method) {
        try {
            for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod(method);
                    m.setAccessible(true);
                    return (T) m.invoke(obj);
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> invokeList(Object obj, String method) {
        Object result = invoke(obj, method);
        return result instanceof List ? (List<T>) result : List.of();
    }
}
