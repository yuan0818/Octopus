package io.yuan.octopus.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "annotation_info")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnotationInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "annotation_name", length = 200)
    private String annotationName;

    @Column(name = "annotation_params", columnDefinition = "TEXT")
    private String annotationParams;

    @Column(name = "target_type", length = 20)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;
}
