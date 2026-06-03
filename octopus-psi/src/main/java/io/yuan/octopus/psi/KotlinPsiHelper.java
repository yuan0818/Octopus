package io.yuan.octopus.psi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility methods for working with Kotlin PSI types, providing
 * language-agnostic equivalents to common Java PSI operations.
 */
public class KotlinPsiHelper {

    private KotlinPsiHelper() {}

    /**
     * Finds all {@code .kt} files within the given scope.
     */
    public static List<KtFile> findKotlinFiles(Project project, GlobalSearchScope scope) {
        Collection<com.intellij.openapi.vfs.VirtualFile> vfs = FilenameIndex.getAllFilesByExt(project, "kt", scope);
        List<KtFile> result = new ArrayList<>();
        for (com.intellij.openapi.vfs.VirtualFile vf : vfs) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile instanceof KtFile ktFile) {
                result.add(ktFile);
            }
        }
        return result;
    }

    /**
     * Extracts all top-level classes / objects from a Kotlin file.
     */
    public static List<KtClassOrObject> getKotlinClasses(KtFile ktFile) {
        return ktFile.getDeclarations().stream()
                .filter(d -> d instanceof KtClassOrObject)
                .map(d -> (KtClassOrObject) d)
                .collect(Collectors.toList());
    }

    /**
     * Returns all named functions declared directly in a Kotlin class/object.
     */
    public static List<KtNamedFunction> getKotlinFunctions(KtClassOrObject classOrObject) {
        return classOrObject.getDeclarations().stream()
                .filter(d -> d instanceof KtNamedFunction)
                .map(d -> (KtNamedFunction) d)
                .collect(Collectors.toList());
    }

    /**
     * Returns all property declarations (val / var) in a Kotlin class/object.
     */
    public static List<KtProperty> getKotlinProperties(KtClassOrObject classOrObject) {
        return classOrObject.getDeclarations().stream()
                .filter(d -> d instanceof KtProperty)
                .map(d -> (KtProperty) d)
                .collect(Collectors.toList());
    }

    /**
     * Resolves the fully-qualified name of a Kotlin annotation by matching
     * its short name against the file's import directives.
     */
    public static String getAnnotationFqn(KtAnnotationEntry entry) {
        KtTypeReference typeRef = entry.getTypeReference();
        if (typeRef == null) return null;
        String shortName = typeRef.getText();

        PsiFile containingFile = entry.getContainingFile();
        if (containingFile instanceof KtFile ktFile) {
            String starImportCandidate = null;
            for (KtImportDirective importDir : ktFile.getImportDirectives()) {
                KtImportAlias alias = importDir.getAlias();
                String importedFqn = importDir.getImportedFqName() != null
                        ? importDir.getImportedFqName().asString() : null;
                if (importedFqn != null) {
                    // Direct import: org.springframework.stereotype.Component → Component
                    if (importedFqn.endsWith("." + shortName)) {
                        return importedFqn;
                    }
                    // Star import: import org.springframework.stereotype.* → org.springframework.stereotype.Component
                    if (importDir.isAllUnder()) {
                        // importedFqn is the package name (without the .*), remember as candidate
                        starImportCandidate = importedFqn + "." + shortName;
                    }
                }
                // Alias import: import org.springframework.stereotype.Component as Comp
                if (alias != null && alias.getName() != null && alias.getName().equals(shortName)) {
                    return importDir.getImportedFqName() != null
                            ? importDir.getImportedFqName().asString() : null;
                }
            }
            // Return star-import candidate if no direct import matched
            if (starImportCandidate != null) {
                return starImportCandidate;
            }
        }
        return shortName; // fallback: return short name
    }

    /**
     * Checks whether a Kotlin annotated element has an annotation with the given FQN.
     */
    public static boolean hasAnnotation(KtAnnotated annotated, String annotationFqn) {
        for (KtAnnotationEntry entry : annotated.getAnnotationEntries()) {
            String fqn = getAnnotationFqn(entry);
            if (annotationFqn.equals(fqn)) return true;
        }
        return false;
    }

    /**
     * Builds the fully-qualified class name for a Kotlin class/object.
     */
    public static String getKotlinClassFqn(KtClassOrObject classOrObject) {
        KtFile ktFile = (KtFile) classOrObject.getContainingFile();
        String packageFqn = ktFile.getPackageFqName().asString();
        String className = classOrObject.getName() != null ? classOrObject.getName() : "";

        // Handle nested / inner classes
        List<String> nameParts = new ArrayList<>();
        nameParts.add(className);
        KtClassOrObject parent = PsiTreeUtil.getParentOfType(classOrObject, KtClassOrObject.class, true);
        while (parent != null) {
            if (parent.getName() != null) {
                nameParts.add(0, parent.getName());
            }
            parent = PsiTreeUtil.getParentOfType(parent, KtClassOrObject.class, true);
        }

        String fullName = String.join(".", nameParts);
        return packageFqn.isEmpty() ? fullName : packageFqn + "." + fullName;
    }

    /**
     * Builds a method signature string in the same format as
     * {@link CallGraphExtractor#buildSignature}.
     */
    public static String buildKotlinSignature(KtClassOrObject clazz, KtNamedFunction func) {
        String className = getKotlinClassFqn(clazz);
        String methodName = func.getName() != null ? func.getName() : "<anonymous>";
        String params = func.getValueParameters().stream()
                .map(p -> p.getTypeReference() != null ? p.getTypeReference().getText() : "kotlin.Any")
                .collect(Collectors.joining(","));
        String returnType = func.getTypeReference() != null ? func.getTypeReference().getText() : "kotlin.Unit";
        return className + "." + methodName + "(" + params + "):" + returnType;
    }

    /**
     * Returns the text of a modifier list for a Kotlin function.
     */
    public static String getModifierText(KtNamedFunction func) {
        KtModifierList modifierList = func.getModifierList();
        return modifierList != null ? modifierList.getText() : "";
    }

    /**
     * Returns the text of a modifier list for a Kotlin property.
     */
    public static String getModifierText(KtProperty property) {
        KtModifierList modifierList = property.getModifierList();
        return modifierList != null ? modifierList.getText() : "";
    }

    /**
     * Extracts the simple call name from a {@code KtCallExpression}, handling
     * both direct calls and qualified calls (via {@code KtDotQualifiedExpression}).
     */
    public static String getCallName(KtCallExpression callExpr) {
        if (callExpr.getCalleeExpression() != null) {
            return callExpr.getCalleeExpression().getText();
        }
        return "";
    }

    /**
     * Determines the receiver expression text for a qualified call (e.g., "obj"
     * in {@code obj.method()}), or null if this is a direct call.
     */
    public static String getReceiverText(KtCallExpression callExpr) {
        if (callExpr.getParent() instanceof KtDotQualifiedExpression dotExpr) {
            if (dotExpr.getReceiverExpression() != null) {
                return dotExpr.getReceiverExpression().getText();
            }
        }
        return null;
    }
}
