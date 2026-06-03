package io.yuan.octopus.repository;

import io.yuan.octopus.entity.ClassDependency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClassDependencyRepository extends JpaRepository<ClassDependency, Long> {

    void deleteByClassId(Long classId);

    void deleteByClassIdIn(List<Long> classIds);

    List<ClassDependency> findByClassId(Long classId);
}
