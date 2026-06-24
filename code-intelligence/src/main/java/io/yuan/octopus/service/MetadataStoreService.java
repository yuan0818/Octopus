package io.yuan.octopus.service;

import com.google.gson.Gson;
import io.yuan.octopus.domain.*;
import io.yuan.octopus.entity.*;
import io.yuan.octopus.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MetadataStoreService {

    private final Gson gson = new Gson();

    private final PackageInfoRepository packageInfoRepository;
    private final ClassInfoRepository classInfoRepository;
    private final MethodInfoRepository methodInfoRepository;
    private final FieldInfoRepository fieldInfoRepository;
    private final AnnotationInfoRepository annotationInfoRepository;
    private final InheritanceRelationRepository inheritanceRelationRepository;
    private final ClassInterfaceRelationRepository classInterfaceRelationRepository;
    private final ClassDependencyRepository classDependencyRepository;

    public MetadataStoreService(
            PackageInfoRepository packageInfoRepository,
            ClassInfoRepository classInfoRepository,
            MethodInfoRepository methodInfoRepository,
            FieldInfoRepository fieldInfoRepository,
            AnnotationInfoRepository annotationInfoRepository,
            InheritanceRelationRepository inheritanceRelationRepository,
            ClassInterfaceRelationRepository classInterfaceRelationRepository,
            ClassDependencyRepository classDependencyRepository) {
        this.packageInfoRepository = packageInfoRepository;
        this.classInfoRepository = classInfoRepository;
        this.methodInfoRepository = methodInfoRepository;
        this.fieldInfoRepository = fieldInfoRepository;
        this.annotationInfoRepository = annotationInfoRepository;
        this.inheritanceRelationRepository = inheritanceRelationRepository;
        this.classInterfaceRelationRepository = classInterfaceRelationRepository;
        this.classDependencyRepository = classDependencyRepository;
    }

    // -------------------------------------------------------------------------
    // Full store (first-time analysis or fallback)
    // -------------------------------------------------------------------------

    @Transactional
    public void storeMetadata(Long projectId, String branch, ParseResult parseResult) {
        clearProjectMetadata(projectId, branch);
        storeMetadataInternal(projectId, branch, parseResult);
    }

    // -------------------------------------------------------------------------
    // Incremental (tag-to-tag diff) store
    // -------------------------------------------------------------------------

    /**
     * Incrementally updates metadata for a project by:
     * <ol>
     *   <li>Removing stale class data only for files listed in {@code diffResult}
     *       (changed + deleted files).</li>
     *   <li>Re-inserting fresh metadata for files contained in {@code parseResult}
     *       (i.e. the re-parsed changed files; deleted files produce no new rows).</li>
     * </ol>
     *
     * @param projectId   project to update
     * @param parseResult parse result containing only the changed (non-deleted) files
     * @param diffResult  diff describing which files changed or were deleted between tags
     */
    @Transactional
    public void updateMetadataDiff(Long projectId, String branch, ParseResult parseResult, DiffResult diffResult) {
        Set<String> affectedFiles = new HashSet<>();
        affectedFiles.addAll(diffResult.changedFilePaths());
        affectedFiles.addAll(diffResult.deletedFilePaths());

        for (String sourceFilePath : affectedFiles) {
            clearMetadataForSourceFile(projectId, branch, sourceFilePath);
        }

        storeMetadataInternal(projectId, branch, parseResult);
    }

    /**
     * Deletes all metadata rows (classes, methods, fields, annotations, relations)
     * that belong to a single source file within the given project and branch.
     * Orphaned package entries are cleaned up as well.
     */
    @Transactional
    public void clearMetadataForSourceFile(Long projectId, String branch, String sourceFilePath) {
        List<ClassInfo> classes = classInfoRepository
                .findByProjectIdAndBranchAndSourceFilePath(projectId, branch, sourceFilePath);
        if (classes.isEmpty()) {
            return;
        }

        List<Long> classIds = classes.stream().map(ClassInfo::getId).toList();

        List<Long> methodIds = classes.stream()
                .flatMap(c -> methodInfoRepository.findByClassInfoId(c.getId()).stream())
                .map(MethodInfo::getId)
                .toList();

        List<Long> fieldIds = classes.stream()
                .flatMap(c -> fieldInfoRepository.findByClassId(c.getId()).stream())
                .map(FieldInfo::getId)
                .toList();

        if (!methodIds.isEmpty()) {
            annotationInfoRepository.deleteByTargetTypeAndTargetIdIn("METHOD", methodIds);
        }
        annotationInfoRepository.deleteByTargetTypeAndTargetIdIn("CLASS", classIds);
        if (!fieldIds.isEmpty()) {
            annotationInfoRepository.deleteByTargetTypeAndTargetIdIn("FIELD", fieldIds);
        }

        for (Long classId : classIds) {
            methodInfoRepository.deleteByClassInfoId(classId);
            fieldInfoRepository.deleteByClassId(classId);
            classInterfaceRelationRepository.deleteByClassId(classId);
            classDependencyRepository.deleteByClassId(classId);
        }

        for (ClassInfo c : classes) {
            inheritanceRelationRepository.deleteByProjectIdAndBranchAndChildQualifiedName(
                    projectId, branch, c.getQualifiedName());
        }

        Set<Long> packageIds = classes.stream()
                .map(ClassInfo::getPackageId)
                .collect(Collectors.toSet());

        classInfoRepository.deleteAll(classes);

        for (Long packageId : packageIds) {
            if (classInfoRepository.countByProjectIdAndBranchAndPackageId(projectId, branch, packageId) == 0) {
                packageInfoRepository.deleteById(packageId);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Full clear
    // -------------------------------------------------------------------------

    @Transactional
    public void clearProjectMetadata(Long projectId, String branch) {
        List<ClassInfo> classes = classInfoRepository
                .findByProjectIdAndBranch(projectId, branch);
        List<Long> classIds = classes.stream().map(ClassInfo::getId).toList();

        if (!classIds.isEmpty()) {
            List<Long> methodIds = classes.stream()
                    .flatMap(c -> methodInfoRepository.findByClassInfoId(c.getId()).stream())
                    .map(MethodInfo::getId)
                    .toList();

            List<Long> fieldIds = classes.stream()
                    .flatMap(c -> fieldInfoRepository.findByClassId(c.getId()).stream())
                    .map(FieldInfo::getId)
                    .toList();

            if (!methodIds.isEmpty()) {
                annotationInfoRepository.deleteByTargetTypeAndTargetIdIn("METHOD", methodIds);
            }
            annotationInfoRepository.deleteByTargetTypeAndTargetIdIn("CLASS", classIds);
            if (!fieldIds.isEmpty()) {
                annotationInfoRepository.deleteByTargetTypeAndTargetIdIn("FIELD", fieldIds);
            }

            methodInfoRepository.deleteByClassInfoIdIn(classIds);
            fieldInfoRepository.deleteByClassIdIn(classIds);
            classInterfaceRelationRepository.deleteByClassIdIn(classIds);
            classDependencyRepository.deleteByClassIdIn(classIds);
        }

        classInfoRepository.deleteByProjectIdAndBranch(projectId, branch);
        inheritanceRelationRepository.deleteByProjectIdAndBranch(projectId, branch);
        packageInfoRepository.deleteByProjectIdAndBranch(projectId, branch);
    }

    // -------------------------------------------------------------------------
    // Internal shared store logic
    // -------------------------------------------------------------------------

    private void storeMetadataInternal(Long projectId, String branch, ParseResult parseResult) {
        Map<String, PackageInfo> packageCache = new HashMap<>();

        for (ParsedFile parsedFile : parseResult.parsedFiles()) {
            for (ParsedClass parsedClass : parsedFile.classes()) {
                String packageName = parsedFile.packageName();
                PackageInfo packageInfo = packageCache.computeIfAbsent(packageName, pn -> {
                    PackageInfo pi = PackageInfo.builder()
                            .projectId(projectId)
                            .branch(branch)
                            .packageName(pn)
                            .build();
                    return packageInfoRepository.save(pi);
                });

                ClassInfo classInfo = ClassInfo.builder()
                        .projectId(projectId)
                        .branch(branch)
                        .packageId(packageInfo.getId())
                        .qualifiedName(parsedClass.qualifiedName())
                        .simpleName(parsedClass.simpleName())
                        .classType(parsedClass.classType())
                        .modifiers(String.join(" ", parsedClass.modifiers()))
                        .sourceFilePath(parsedClass.sourceFilePath())
                        .parentClass(parsedClass.parentClass())
                        .build();
                classInfo = classInfoRepository.save(classInfo);

                for (ParsedMethod method : parsedClass.methods()) {
                    MethodInfo methodInfo = MethodInfo.builder()
                            .classInfo(classInfo)
                            .methodSignature(method.signature())
                            .methodName(method.name())
                            .parameterTypes(toJson(method.parameterTypes()))
                            .returnType(method.returnType())
                            .modifiers(String.join(" ", method.modifiers()))
                            .sourceCode(method.sourceCode())
                            .build();
                    methodInfo = methodInfoRepository.save(methodInfo);
                    saveAnnotations(method.annotations(), "METHOD", methodInfo.getId());
                }

                for (ParsedField field : parsedClass.fields()) {
                    FieldInfo fieldInfo = FieldInfo.builder()
                            .classId(classInfo.getId())
                            .fieldName(field.name())
                            .fieldType(field.type())
                            .modifiers(String.join(" ", field.modifiers()))
                            .build();
                    fieldInfo = fieldInfoRepository.save(fieldInfo);
                    saveAnnotations(field.annotations(), "FIELD", fieldInfo.getId());
                }

                saveAnnotations(parsedClass.annotations(), "CLASS", classInfo.getId());

                if (parsedClass.parentClass() != null && !parsedClass.parentClass().isEmpty()) {
                    inheritanceRelationRepository.save(InheritanceRelation.builder()
                            .projectId(projectId)
                            .branch(branch)
                            .childQualifiedName(parsedClass.qualifiedName())
                            .parentQualifiedName(parsedClass.parentClass())
                            .relationType("EXTENDS")
                            .build());
                }

                if (parsedClass.interfaces() != null) {
                    for (String iface : parsedClass.interfaces()) {
                        inheritanceRelationRepository.save(InheritanceRelation.builder()
                                .projectId(projectId)
                                .branch(branch)
                                .childQualifiedName(parsedClass.qualifiedName())
                                .parentQualifiedName(iface)
                                .relationType("IMPLEMENTS")
                                .build());
                        classInterfaceRelationRepository.save(ClassInterfaceRelation.builder()
                                .classId(classInfo.getId())
                                .interfaceQualifiedName(iface)
                                .build());
                    }
                }

                if (parsedClass.externalDependencies() != null) {
                    for (ExternalDependency dep : parsedClass.externalDependencies()) {
                        classDependencyRepository.save(ClassDependency.builder()
                                .classId(classInfo.getId())
                                .dependencyQualifiedName(dep.qualifiedName())
                                .dependencyType(dep.type())
                                .build());
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private void saveAnnotations(List<ParsedAnnotation> annotations, String targetType, Long targetId) {
        if (annotations == null) return;
        for (ParsedAnnotation annotation : annotations) {
            annotationInfoRepository.save(AnnotationInfo.builder()
                    .annotationName(annotation.name())
                    .annotationParams(toJson(annotation.parameters()))
                    .targetType(targetType)
                    .targetId(targetId)
                    .build());
        }
    }

    private String toJson(Object value) {
        if (value == null) return null;
        return gson.toJson(value);
    }
}
