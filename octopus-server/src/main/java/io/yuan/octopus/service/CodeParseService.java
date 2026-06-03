package io.yuan.octopus.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.yuan.octopus.common.CallType;
import io.yuan.octopus.common.ClassType;
import io.yuan.octopus.common.DependencyType;
import io.yuan.octopus.common.JavaVersion;
import io.yuan.octopus.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CodeParseService {

    private static final Logger log = LoggerFactory.getLogger(CodeParseService.class);

    public ParseResult parseProject(JavaProject project) {
        return parseProject(project, List.of());
    }

    /**
     * Parses all {@code .java} files in the project's source directories, using a combined
     * type solver that includes {@code extraSourceDirs} for cross-module symbol resolution.
     * Only files from the project's own source directories are included in the result;
     * {@code extraSourceDirs} are used solely to resolve references to sibling modules.
     *
     * @param project        the project to parse
     * @param extraSourceDirs additional source directories (e.g., sibling sub-modules in a monorepo)
     *                        to add to the symbol solver for cross-module type resolution
     * @return parse result containing only the project's own files
     */
    public ParseResult parseProject(JavaProject project, Collection<Path> extraSourceDirs) {
        List<ParsedFile> parsedFiles = new ArrayList<>();
        List<ParseError> errors = new ArrayList<>();

        JavaParser javaParser = createJavaParser(project, extraSourceDirs);

        // Collect all source file paths from project source directories
        Set<String> projectImports = new HashSet<>();

        for (Path sourceDir : project.sourceDirectories()) {
            if (!Files.isDirectory(sourceDir)) {
                log.warn("Source directory does not exist: {}", sourceDir);
                continue;
            }

            List<Path> javaFiles = collectJavaFiles(sourceDir);
            for (Path javaFile : javaFiles) {
                parseOne(javaParser, javaFile, javaFile.toString(), projectImports, parsedFiles, errors);
            }
        }

        return new ParseResult(parsedFiles, errors);
    }

    /**
     * Parses only the specified {@code .java} files within the context of the given project
     * (so that the symbol solver can still resolve cross-file references).
     *
     * @param project   the project whose source directories are used for symbol resolution
     * @param filePaths absolute paths of {@code .java} files to parse (typically the diff-changed set)
     * @return parse result containing only the requested files
     */
    public ParseResult parseFiles(JavaProject project, Set<String> filePaths) {
        if (filePaths.isEmpty()) {
            return new ParseResult(List.of(), List.of());
        }

        List<ParsedFile> parsedFiles = new ArrayList<>();
        List<ParseError> errors = new ArrayList<>();

        JavaParser javaParser = createJavaParser(project, List.of());
        Set<String> projectImports = new HashSet<>();

        for (String filePath : filePaths) {
            Path javaFile = Path.of(filePath);
            if (!Files.exists(javaFile)) {
                log.debug("Skipping non-existent file (likely deleted): {}", filePath);
                continue;
            }
            parseOne(javaParser, javaFile, filePath, projectImports, parsedFiles, errors);
        }

        return new ParseResult(parsedFiles, errors);
    }

    private void parseOne(JavaParser javaParser, Path javaFile, String displayPath,
                          Set<String> projectImports,
                          List<ParsedFile> parsedFiles, List<ParseError> errors) {
        try {
            var parseResult = javaParser.parse(javaFile);
            if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                CompilationUnit cu = parseResult.getResult().get();
                ParsedFile parsedFile = extractFileInfo(cu, displayPath, projectImports);
                parsedFiles.add(parsedFile);
            } else {
                String errorMsg = parseResult.getProblems().stream()
                        .map(p -> p.getVerboseMessage())
                        .collect(Collectors.joining("; "));
                errors.add(new ParseError(displayPath, errorMsg));
                log.warn("Failed to parse file {}: {}", displayPath, errorMsg);
            }
        } catch (IOException e) {
            errors.add(new ParseError(displayPath, "IO error: " + e.getMessage()));
            log.warn("IO error reading file {}: {}", displayPath, e.getMessage());
        } catch (Exception e) {
            errors.add(new ParseError(displayPath, "Unexpected error: " + e.getMessage()));
            log.warn("Unexpected error parsing file {}: {}", displayPath, e.getMessage());
        }
    }

    private JavaParser createJavaParser(JavaProject project, Collection<Path> extraSourceDirs) {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(mapLanguageLevel(project.javaVersion()));

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());

        Set<Path> addedDirs = new HashSet<>();
        for (Path sourceDir : project.sourceDirectories()) {
            if (Files.isDirectory(sourceDir) && addedDirs.add(sourceDir)) {
                typeSolver.add(new JavaParserTypeSolver(sourceDir));
            }
        }
        // Add sibling module source dirs for cross-module symbol resolution
        for (Path sourceDir : extraSourceDirs) {
            if (Files.isDirectory(sourceDir) && addedDirs.add(sourceDir)) {
                typeSolver.add(new JavaParserTypeSolver(sourceDir));
            }
        }
        config.setSymbolResolver(new JavaSymbolSolver(typeSolver));

        return new JavaParser(config);
    }

    private ParserConfiguration.LanguageLevel mapLanguageLevel(JavaVersion javaVersion) {
        return switch (javaVersion) {
            case JAVA_8 -> ParserConfiguration.LanguageLevel.JAVA_8;
            case JAVA_11 -> ParserConfiguration.LanguageLevel.JAVA_11;
            case JAVA_17 -> ParserConfiguration.LanguageLevel.JAVA_17;
            case JAVA_21 -> {
                try {
                    yield ParserConfiguration.LanguageLevel.valueOf("JAVA_21");
                } catch (IllegalArgumentException e) {
                    log.info("JAVA_21 language level not available in JavaParser, falling back to JAVA_17");
                    yield ParserConfiguration.LanguageLevel.JAVA_17;
                }
            }
        };
    }

    private List<Path> collectJavaFiles(Path sourceDir) {
        List<Path> javaFiles = new ArrayList<>();
        try {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        javaFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Failed to visit file: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to walk source directory {}: {}", sourceDir, e.getMessage());
        }
        return javaFiles;
    }

    private ParsedFile extractFileInfo(CompilationUnit cu, String filePath, Set<String> projectImports) {
        String packageName = cu.getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .orElse("");

        List<String> imports = cu.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .toList();

        // Track all imports for external dependency detection
        projectImports.addAll(imports);

        List<ParsedClass> classes = new ArrayList<>();

        for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
            extractClassInfo(typeDecl, packageName, filePath, imports, classes);
        }

        return new ParsedFile(filePath, packageName, imports, classes);
    }

    private void extractClassInfo(TypeDeclaration<?> typeDecl, String packageName,
                                  String filePath, List<String> imports,
                                  List<ParsedClass> classes) {
        String simpleName = typeDecl.getNameAsString();
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

        ClassType classType = determineClassType(typeDecl);
        List<String> modifiers = extractModifiers(typeDecl);

        String parentClass = null;
        List<String> interfaces = new ArrayList<>();

        if (typeDecl instanceof ClassOrInterfaceDeclaration coid) {
            if (!coid.isInterface()) {
                parentClass = coid.getExtendedTypes().stream()
                        .findFirst()
                        .map(ClassOrInterfaceType::getNameAsString)
                        .orElse(null);
            }
            interfaces = coid.getImplementedTypes().stream()
                    .map(ClassOrInterfaceType::getNameAsString)
                    .collect(Collectors.toList());
            // For interfaces, extended types are "parent interfaces"
            if (coid.isInterface()) {
                interfaces = coid.getExtendedTypes().stream()
                        .map(ClassOrInterfaceType::getNameAsString)
                        .collect(Collectors.toList());
            }
        } else if (typeDecl instanceof EnumDeclaration enumDecl) {
            interfaces = enumDecl.getImplementedTypes().stream()
                    .map(ClassOrInterfaceType::getNameAsString)
                    .collect(Collectors.toList());
        } else if (typeDecl instanceof RecordDeclaration recordDecl) {
            interfaces = recordDecl.getImplementedTypes().stream()
                    .map(ClassOrInterfaceType::getNameAsString)
                    .collect(Collectors.toList());
        }

        List<ParsedMethod> methods = extractMethods(typeDecl, qualifiedName);
        List<ParsedField> fields = extractFields(typeDecl);
        List<ParsedAnnotation> annotations = extractAnnotations(typeDecl);

        // Determine external dependencies from imports and unresolved types
        List<ExternalDependency> externalDependencies = detectExternalDependencies(typeDecl, imports);

        classes.add(new ParsedClass(
                qualifiedName, simpleName, classType, modifiers,
                parentClass, interfaces, methods, fields, annotations,
                filePath, externalDependencies
        ));

        // Process nested types
        for (BodyDeclaration<?> member : typeDecl.getMembers()) {
            if (member instanceof TypeDeclaration<?> nestedType) {
                extractClassInfo(nestedType, packageName, filePath, imports, classes);
            }
        }
    }

    private ClassType determineClassType(TypeDeclaration<?> typeDecl) {
        if (typeDecl instanceof ClassOrInterfaceDeclaration coid) {
            if (coid.isInterface()) return ClassType.INTERFACE;
            return ClassType.CLASS;
        }
        if (typeDecl instanceof EnumDeclaration) return ClassType.ENUM;
        if (typeDecl instanceof RecordDeclaration) return ClassType.RECORD;
        if (typeDecl instanceof AnnotationDeclaration) return ClassType.ANNOTATION;
        return ClassType.CLASS;
    }

    private List<String> extractModifiers(NodeWithModifiers<?> node) {
        return node.getModifiers().stream()
                .map(Modifier::getKeyword)
                .map(Modifier.Keyword::asString)
                .toList();
    }

    private List<ParsedAnnotation> extractAnnotations(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(this::convertAnnotation)
                .toList();
    }

    private ParsedAnnotation convertAnnotation(AnnotationExpr annotationExpr) {
        String name = annotationExpr.getNameAsString();
        Map<String, String> parameters = new LinkedHashMap<>();

        if (annotationExpr instanceof SingleMemberAnnotationExpr single) {
            parameters.put("value", single.getMemberValue().toString());
        } else if (annotationExpr instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                parameters.put(pair.getNameAsString(), pair.getValue().toString());
            }
        }
        // MarkerAnnotationExpr has no parameters

        return new ParsedAnnotation(name, parameters);
    }

    private List<ParsedField> extractFields(TypeDeclaration<?> typeDecl) {
        List<ParsedField> fields = new ArrayList<>();
        for (FieldDeclaration fieldDecl : typeDecl.getFields()) {
            List<String> modifiers = extractModifiers(fieldDecl);
            String fieldType = fieldDecl.getElementType().asString();
            for (VariableDeclarator var : fieldDecl.getVariables()) {
                fields.add(new ParsedField(var.getNameAsString(), fieldType, modifiers));
            }
        }
        return fields;
    }

    private List<ParsedMethod> extractMethods(TypeDeclaration<?> typeDecl, String qualifiedClassName) {
        List<ParsedMethod> methods = new ArrayList<>();

        for (MethodDeclaration methodDecl : typeDecl.getMethods()) {
            methods.add(extractMethod(methodDecl, qualifiedClassName));
        }

        // Extract constructors as well
        if (typeDecl instanceof ClassOrInterfaceDeclaration coid) {
            for (ConstructorDeclaration ctorDecl : coid.getConstructors()) {
                methods.add(extractConstructor(ctorDecl, qualifiedClassName));
            }
        } else if (typeDecl instanceof EnumDeclaration enumDecl) {
            for (ConstructorDeclaration ctorDecl : enumDecl.getConstructors()) {
                methods.add(extractConstructor(ctorDecl, qualifiedClassName));
            }
        } else if (typeDecl instanceof RecordDeclaration recordDecl) {
            for (ConstructorDeclaration ctorDecl : recordDecl.getConstructors()) {
                methods.add(extractConstructor(ctorDecl, qualifiedClassName));
            }
        }

        return methods;
    }

    private ParsedMethod extractMethod(MethodDeclaration methodDecl, String qualifiedClassName) {
        String name = methodDecl.getNameAsString();
        List<String> parameterTypes = methodDecl.getParameters().stream()
                .map(p -> p.getType().asString())
                .toList();
        String returnType = methodDecl.getType().asString();
        List<String> modifiers = extractModifiers(methodDecl);
        String sourceCode = methodDecl.toString();
        List<ParsedAnnotation> annotations = extractAnnotations(methodDecl);

        String signature = buildMethodSignature(qualifiedClassName, name, parameterTypes, returnType);

        // Extract method calls from the method body
        List<MethodCall> methodCalls = new ArrayList<>();
        List<ExternalDependency> unresolvedDeps = new ArrayList<>();
        extractMethodCalls(methodDecl, methodCalls, unresolvedDeps);

        return new ParsedMethod(name, signature, parameterTypes, returnType,
                modifiers, sourceCode, methodCalls, annotations);
    }

    private ParsedMethod extractConstructor(ConstructorDeclaration ctorDecl, String qualifiedClassName) {
        String name = "<init>";
        List<String> parameterTypes = ctorDecl.getParameters().stream()
                .map(p -> p.getType().asString())
                .toList();
        String returnType = "void";
        List<String> modifiers = extractModifiers(ctorDecl);
        String sourceCode = ctorDecl.toString();
        List<ParsedAnnotation> annotations = extractAnnotations(ctorDecl);

        String signature = buildMethodSignature(qualifiedClassName, name, parameterTypes, returnType);

        List<MethodCall> methodCalls = new ArrayList<>();
        List<ExternalDependency> unresolvedDeps = new ArrayList<>();
        extractMethodCalls(ctorDecl, methodCalls, unresolvedDeps);

        return new ParsedMethod(name, signature, parameterTypes, returnType,
                modifiers, sourceCode, methodCalls, annotations);
    }

    private String buildMethodSignature(String className, String methodName,
                                        List<String> parameterTypes, String returnType) {
        String params = String.join(",", parameterTypes);
        return className + "." + methodName + "(" + params + "):" + returnType;
    }

    private void extractMethodCalls(CallableDeclaration<?> callableDecl,
                                    List<MethodCall> methodCalls,
                                    List<ExternalDependency> unresolvedDeps) {
        callableDecl.findAll(MethodCallExpr.class).forEach(methodCallExpr -> {
            try {
                ResolvedMethodDeclaration resolved = methodCallExpr.resolve();
                String targetClassName = resolved.declaringType().getQualifiedName();
                String targetMethodName = resolved.getName();
                List<String> argumentTypes = new ArrayList<>();
                for (int i = 0; i < resolved.getNumberOfParams(); i++) {
                    argumentTypes.add(resolved.getParam(i).getType().describe());
                }

                CallType callType = determineCallType(methodCallExpr, resolved);
                int lineNumber = methodCallExpr.getBegin()
                        .map(pos -> pos.line)
                        .orElse(0);

                methodCalls.add(new MethodCall(
                        targetClassName, targetMethodName, argumentTypes, callType, lineNumber
                ));
            } catch (UnsolvedSymbolException e) {
                // Third-party or unresolvable call — record as external dependency, skip the call
                String unresolvedName = e.getName();
                unresolvedDeps.add(new ExternalDependency(unresolvedName, DependencyType.TYPE_REFERENCE));
                log.debug("Unresolved method call in {}: {}", callableDecl.getNameAsString(), unresolvedName);
            } catch (Exception e) {
                // Other resolution failures — log and skip
                log.debug("Failed to resolve method call in {}: {}",
                        callableDecl.getNameAsString(), e.getMessage());
            }
        });
    }

    private CallType determineCallType(MethodCallExpr methodCallExpr, ResolvedMethodDeclaration resolved) {
        // Check for super.method() calls
        if (methodCallExpr.getScope().isPresent()) {
            Expression scope = methodCallExpr.getScope().get();
            if (scope instanceof SuperExpr) {
                return CallType.SUPER;
            }
        }

        // Check if the declaring type is an interface
        try {
            ResolvedReferenceTypeDeclaration declaringType = resolved.declaringType();
            if (declaringType.isInterface()) {
                return CallType.INTERFACE;
            }
        } catch (Exception e) {
            log.debug("Failed to determine declaring type for call type: {}", e.getMessage());
        }

        return CallType.DIRECT;
    }

    private List<ExternalDependency> detectExternalDependencies(TypeDeclaration<?> typeDecl,
                                                                List<String> imports) {
        List<ExternalDependency> dependencies = new ArrayList<>();
        Set<String> addedDeps = new HashSet<>();

        // Check imports that can't be resolved by the type solver
        for (String importName : imports) {
            // Skip java.lang imports — they're always available
            if (importName.startsWith("java.lang.")) continue;

            try {
                // Try to resolve the import as a type in the compilation unit's symbol solver
                typeDecl.findCompilationUnit().ifPresent(cu -> {
                    try {
                        cu.findFirst(ClassOrInterfaceType.class,
                                t -> t.getNameAsString().equals(getSimpleName(importName)));
                    } catch (Exception ignored) {
                        // Resolution attempt — we'll check below
                    }
                });

                // If the import is for a type that can't be found in project sources,
                // it's an external dependency. We detect this by checking if any type
                // in the compilation unit references it and fails to resolve.
                if (!isProjectType(typeDecl, importName) && addedDeps.add(importName)) {
                    dependencies.add(new ExternalDependency(importName, DependencyType.IMPORT));
                }
            } catch (Exception e) {
                // If we can't determine, treat as external
                if (addedDeps.add(importName)) {
                    dependencies.add(new ExternalDependency(importName, DependencyType.IMPORT));
                }
            }
        }

        // Also collect unresolved types from method calls
        for (MethodDeclaration method : typeDecl.getMethods()) {
            method.findAll(MethodCallExpr.class).forEach(mce -> {
                try {
                    mce.resolve();
                } catch (UnsolvedSymbolException e) {
                    String name = e.getName();
                    if (addedDeps.add(name)) {
                        dependencies.add(new ExternalDependency(name, DependencyType.TYPE_REFERENCE));
                    }
                } catch (Exception ignored) {
                    // Other resolution errors
                }
            });
        }

        return dependencies;
    }

    private boolean isProjectType(TypeDeclaration<?> typeDecl, String qualifiedName) {
        try {
            var cu = typeDecl.findCompilationUnit().orElse(null);
            if (cu == null) return false;

            // Check if any type in the same compilation unit matches
            for (TypeDeclaration<?> type : cu.getTypes()) {
                String pkg = cu.getPackageDeclaration()
                        .map(PackageDeclaration::getNameAsString)
                        .orElse("");
                String fqn = pkg.isEmpty() ? type.getNameAsString() : pkg + "." + type.getNameAsString();
                if (fqn.equals(qualifiedName)) return true;
            }

            // Try to resolve via the symbol solver using a ClassOrInterfaceType reference
            String simpleName = getSimpleName(qualifiedName);
            var found = cu.findFirst(ClassOrInterfaceType.class, t -> {
                if (!t.getNameAsString().equals(simpleName)) return false;
                try {
                    var resolved = t.resolve();
                    if (resolved.isReferenceType()) {
                        return resolved.asReferenceType().getQualifiedName().equals(qualifiedName);
                    }
                    return false;
                } catch (Exception e) {
                    return false;
                }
            });

            return found.isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private String getSimpleName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }
}
