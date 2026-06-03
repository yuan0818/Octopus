package io.yuan.octopus.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "inheritance_relation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InheritanceRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    /** Branch this relation belongs to. */
    @Column(name = "branch", length = 200)
    private String branch;

    @Column(name = "child_qualified_name", length = 500)
    private String childQualifiedName;

    @Column(name = "parent_qualified_name", length = 500)
    private String parentQualifiedName;

    @Column(name = "relation_type", length = 20)
    private String relationType;
}
