package io.yuan.octopus.repository;

import io.yuan.octopus.entity.ClassInterfaceRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClassInterfaceRelationRepository extends JpaRepository<ClassInterfaceRelation, Long> {

    void deleteByClassId(Long classId);

    void deleteByClassIdIn(List<Long> classIds);

    List<ClassInterfaceRelation> findByClassId(Long classId);
}
