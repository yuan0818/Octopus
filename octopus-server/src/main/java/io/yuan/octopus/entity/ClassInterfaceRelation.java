package io.yuan.octopus.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "class_interface_relation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassInterfaceRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "interface_qualified_name", length = 500)
    private String interfaceQualifiedName;
}
