package io.yuan.octopus.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "package_info")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackageInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    /** Branch this package record belongs to. */
    @Column(name = "branch", length = 200)
    private String branch;

    @Column(name = "package_name", length = 500)
    private String packageName;
}
