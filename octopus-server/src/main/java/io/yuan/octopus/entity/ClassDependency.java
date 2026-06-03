package io.yuan.octopus.entity;

import io.yuan.octopus.common.DependencyType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "class_dependency")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "dependency_qualified_name", columnDefinition = "TEXT")
    private String dependencyQualifiedName;

    @Enumerated(EnumType.STRING)
    @Column(name = "dependency_type", length = 20)
    private DependencyType dependencyType;
}
