package io.yuan.octopus.repository;

import io.yuan.octopus.entity.InheritanceRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InheritanceRelationRepository extends JpaRepository<InheritanceRelation, Long> {

    void deleteByProjectId(Long projectId);

    void deleteByProjectIdAndChildQualifiedName(Long projectId, String childQualifiedName);

    void deleteByProjectIdAndBranch(Long projectId, String branch);

    void deleteByProjectIdAndBranchAndChildQualifiedName(Long projectId, String branch, String childQualifiedName);

    List<InheritanceRelation> findByChildQualifiedName(String childQualifiedName);

    List<InheritanceRelation> findByParentQualifiedNameAndRelationType(String parentQualifiedName, String relationType);
}
