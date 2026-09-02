package io.yuan.octopus.repository;

import io.yuan.octopus.entity.FieldInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FieldInfoRepository extends JpaRepository<FieldInfo, Long> {

    void deleteByClassId(Long classId);

    void deleteByClassIdIn(List<Long> classIds);

    List<FieldInfo> findByClassId(Long classId);

    List<FieldInfo> findByClassIdIn(List<Long> classIds);
}
