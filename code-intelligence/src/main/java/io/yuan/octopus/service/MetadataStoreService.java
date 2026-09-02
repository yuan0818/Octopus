package io.yuan.octopus.service;

import com.google.gson.Gson;
import io.yuan.octopus.domain.*;
import io.yuan.octopus.entity.*;
import io.yuan.octopus.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 元数据存储服务——版本化存储（按 (project_id, branch, commit_id) 隔离）。
 * <p>
 * 写入语义（对应 REQ-DIFF-004）：
 * <ol>
 *   <li>将该 (project_id, branch) 下所有 {@code is_head=true} 的旧行翻转为 {@code is_head=false}</li>
 *   <li>插入本次分析的全量快照，新行带 {@code commit_id} 且 {@code is_head=true}</li>
 * </ol>
 * 不再采用覆盖式 DELETE + INSERT，历史版本得以保留以支持跨版本 Diff。
 */
@Service
public class MetadataStoreService {

    private static final Logger log = LoggerFactory.getLogger(MetadataStoreService.class);

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
    // Version-aware full store (default analysis path)
    // -------------------------------------------------------------------------

    /**
     * 版本化全量写入：标记旧 HEAD 为 false，再插入本次 commit 的全量快照（is_head=true）。
     * <p>
     * 对应业务规则 BR-DIFF-001 / BR-DIFF-002 / BR-DIFF-016 / BR-DIFF-017。
     */
    @Transactional
    public void storeMetadata(Long projectId, String branch, String commitId, ParseResult parseResult) {
        markOldHeadAsFalse(projectId, branch);
        // inheritance_relation 为非版本化派生数据（按 qualified_name 关联）：
        // 全量写入前清空旧快照，否则每次分析 EXTENDS/IMPLEMENTS 行翻倍、getImplementors 返回重复
        inheritanceRelationRepository.deleteByProjectIdAndBranch(projectId, branch);
        storeMetadataInternal(projectId, branch, commitId, parseResult);
    }

    // -------------------------------------------------------------------------
    // Version-aware incremental (tag-to-tag diff) store
    // -------------------------------------------------------------------------

    /**
     * 版本化增量写入：仅对 diff 涉及的文件翻转 HEAD + 重写其元数据。
     * <p>
     * 先翻转这些文件的旧 HEAD 行（按 sourceFilePath 限定），再插入本次 commit 的快照。
     */
    @Transactional
    public void updateMetadataDiff(Long projectId, String branch, String commitId,
                                   ParseResult parseResult, DiffResult diffResult) {
        Set<String> affectedFiles = new HashSet<>();
        affectedFiles.addAll(diffResult.changedFilePaths());
        affectedFiles.addAll(diffResult.deletedFilePaths());

        // 翻转 HEAD 前收集受影响文件的当前 HEAD 行：
        // 注解需要按 target 行翻转（未变更文件的注解不能动），继承关系需要按类清理
        List<Long> headClassIds = new ArrayList<>();
        List<Long> headMethodIds = new ArrayList<>();
        List<Long> headFieldIds = new ArrayList<>();
        List<String> affectedClassNames = new ArrayList<>();
        for (String file : affectedFiles) {
            List<ClassInfo> headClasses = classInfoRepository
                    .findByProjectIdAndBranchAndSourceFilePathAndIsHeadTrue(projectId, branch, file);
            for (ClassInfo headClass : headClasses) {
                headClassIds.add(headClass.getId());
                if (headClass.getQualifiedName() != null) {
                    affectedClassNames.add(headClass.getQualifiedName());
                }
                for (MethodInfo method : methodInfoRepository.findByClassInfoIdAndIsHeadTrue(headClass.getId())) {
                    headMethodIds.add(method.getId());
                }
                for (FieldInfo field : fieldInfoRepository.findByClassId(headClass.getId())) {
                    headFieldIds.add(field.getId());
                }
            }
        }

        markOldHeadAsFalseForFiles(projectId, branch, affectedFiles);

        if (!headClassIds.isEmpty()) {
            annotationInfoRepository.markHeadAsFalseForClassTargets(projectId, branch, headClassIds);
        }
        if (!headMethodIds.isEmpty()) {
            annotationInfoRepository.markHeadAsFalseForMethodTargets(projectId, branch, headMethodIds);
        }
        if (!headFieldIds.isEmpty()) {
            annotationInfoRepository.markHeadAsFalseForFieldTargets(projectId, branch, headFieldIds);
        }

        // 重写/删除的类先清继承关系（deleted 文件的类不再有新行，changed 文件的类由快照重插）
        for (String childName : affectedClassNames) {
            inheritanceRelationRepository.deleteByProjectIdAndBranchAndChildQualifiedName(projectId, branch, childName);
        }

        storeMetadataInternal(projectId, branch, commitId, parseResult);
    }

    // -------------------------------------------------------------------------
    // Full clear (e.g. project deletion) — non-version-aware, deletes all versions
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
    // HEAD marker management
    // -------------------------------------------------------------------------

    /**
     * 翻转 (project_id, branch) 下所有 class/method/annotation 的 HEAD 行。
     * 对应 BR-DIFF-016：写入前 UPDATE SET is_head = false WHERE ... is_head = true。
     */
    private void markOldHeadAsFalse(Long projectId, String branch) {
        int classes = classInfoRepository.markHeadAsFalse(projectId, branch);
        int methods = methodInfoRepository.markHeadAsFalse(projectId, branch);
        int annotations = annotationInfoRepository.markHeadAsFalse(projectId, branch);
        log.debug("Flipped HEAD markers for project {} branch {}: {} classes, {} methods, {} annotations",
                projectId, branch, classes, methods, annotations);
    }

    /**
     * 增量写入：仅翻转 diff 涉及文件的 class/method HEAD 行。
     * 注解的翻转按 target 行进行（见 {@link #updateMetadataDiff}），未变更文件的注解保持 HEAD。
     */
    private void markOldHeadAsFalseForFiles(Long projectId, String branch, Set<String> affectedFiles) {
        int classCount = 0;
        int methodCount = 0;
        for (String file : affectedFiles) {
            classCount += classInfoRepository.markHeadAsFalseForFile(projectId, branch, file);
            methodCount += methodInfoRepository.markHeadAsFalseForFile(projectId, branch, file);
        }
        log.debug("Flipped HEAD markers for {} files in project {} branch {}: {} classes, {} methods",
                affectedFiles.size(), projectId, branch, classCount, methodCount);
    }

    // -------------------------------------------------------------------------
    // Internal shared store logic — inserts full snapshot with version fields
    // -------------------------------------------------------------------------

    private void storeMetadataInternal(Long projectId, String branch, String commitId,
                                       ParseResult parseResult) {
        // 包行按 (project_id, branch, package_name) 复用：旧版本 class 行的 package_id
        // 引用保持有效，包表也不随版本累积膨胀
        Map<String, PackageInfo> packageCache = new HashMap<>();

        for (ParsedFile parsedFile : parseResult.parsedFiles()) {
            for (ParsedClass parsedClass : parsedFile.classes()) {
                String packageName = parsedFile.packageName();
                PackageInfo packageInfo = packageCache.computeIfAbsent(packageName, pn ->
                        packageInfoRepository.findByProjectIdAndBranchAndPackageName(projectId, branch, pn)
                                .orElseGet(() -> packageInfoRepository.save(PackageInfo.builder()
                                        .projectId(projectId)
                                        .branch(branch)
                                        .packageName(pn)
                                        .build())));

                String sourceFilePath = parsedClass.sourceFilePath();
                ClassInfo classInfo = ClassInfo.builder()
                        .projectId(projectId)
                        .branch(branch)
                        .commitId(commitId)
                        .isHead(true)
                        .packageId(packageInfo.getId())
                        .qualifiedName(parsedClass.qualifiedName())
                        .simpleName(parsedClass.simpleName())
                        .classType(parsedClass.classType())
                        .modifiers(String.join(" ", parsedClass.modifiers()))
                        .sourceFilePath(sourceFilePath)
                        .parentClass(parsedClass.parentClass())
                        .build();
                classInfo = classInfoRepository.save(classInfo);

                for (ParsedMethod method : parsedClass.methods()) {
                    MethodInfo methodInfo = MethodInfo.builder()
                            .classInfo(classInfo)
                            .commitId(commitId)
                            .isHead(true)
                            .methodSignature(method.signature())
                            .methodName(method.name())
                            .parameterTypes(toJson(method.parameterTypes()))
                            .returnType(method.returnType())
                            .modifiers(String.join(" ", method.modifiers()))
                            .sourceCode(method.sourceCode())
                            // 行号来自 PSI/JavaParser 解析（ParsedMethod.lineStart/lineEnd）
                            .lineStart(method.lineStart())
                            .lineEnd(method.lineEnd())
                            // 冗余文件路径，加速 JGit git show（避免再 JOIN class_info）
                            .filePath(sourceFilePath)
                            .build();
                    methodInfo = methodInfoRepository.save(methodInfo);
                    saveAnnotations(method.annotations(), "METHOD", methodInfo.getId(),
                            projectId, branch, commitId);
                }

                for (ParsedField field : parsedClass.fields()) {
                    FieldInfo fieldInfo = FieldInfo.builder()
                            .classId(classInfo.getId())
                            .fieldName(field.name())
                            .fieldType(field.type())
                            .modifiers(String.join(" ", field.modifiers()))
                            .build();
                    fieldInfo = fieldInfoRepository.save(fieldInfo);
                    saveAnnotations(field.annotations(), "FIELD", fieldInfo.getId(),
                            projectId, branch, commitId);
                }

                saveAnnotations(parsedClass.annotations(), "CLASS", classInfo.getId(),
                        projectId, branch, commitId);

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

    private void saveAnnotations(List<ParsedAnnotation> annotations, String targetType, Long targetId,
                                 Long projectId, String branch, String commitId) {
        if (annotations == null) return;
        for (ParsedAnnotation annotation : annotations) {
            annotationInfoRepository.save(AnnotationInfo.builder()
                    .projectId(projectId)
                    .branch(branch)
                    .commitId(commitId)
                    .isHead(true)
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
