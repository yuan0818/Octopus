package io.yuan.octopus.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "method_info", indexes = {
    @Index(name = "idx_method_signature", columnList = "method_signature")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MethodInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id", nullable = false)
    private ClassInfo classInfo;

    @Column(name = "method_signature", length = 750)
    private String methodSignature;

    @Column(name = "method_name", length = 200)
    private String methodName;

    @Column(name = "parameter_types", columnDefinition = "TEXT")
    private String parameterTypes;

    @Column(name = "return_type", length = 200)
    private String returnType;

    @Column(name = "modifiers", length = 100)
    private String modifiers;

    @Column(name = "source_code", columnDefinition = "MEDIUMTEXT")
    private String sourceCode;
}
