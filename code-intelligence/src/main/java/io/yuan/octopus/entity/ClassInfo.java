package io.yuan.octopus.entity;

import io.yuan.octopus.common.ClassType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "class_info", indexes = {
    @Index(name = "idx_class_qualified_name", columnList = "qualified_name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    /** Branch this class record belongs to (mirrors {@code JavaProjectEntity.branch}). */
    @Column(name = "branch", length = 200)
    private String branch;

    @Column(name = "package_id")
    private Long packageId;

    @Column(name = "qualified_name", length = 500)
    private String qualifiedName;

    @Column(name = "simple_name", length = 200)
    private String simpleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "class_type", length = 20)
    private ClassType classType;

    @Column(name = "modifiers", length = 100)
    private String modifiers;

    @Column(name = "source_file_path", length = 500)
    private String sourceFilePath;

    @Column(name = "parent_class", length = 500)
    private String parentClass;
}
