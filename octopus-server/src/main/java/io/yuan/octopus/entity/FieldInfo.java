package io.yuan.octopus.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "field_info")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FieldInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "field_name", length = 200)
    private String fieldName;

    @Column(name = "field_type", length = 200)
    private String fieldType;

    @Column(name = "modifiers", length = 100)
    private String modifiers;
}
