package io.yuan.octopus.repository;

import io.yuan.octopus.entity.MethodInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MethodInfoRepository extends JpaRepository<MethodInfo, Long> {

    void deleteByClassInfoId(Long classId);

    void deleteByClassInfoIdIn(List<Long> classIds);

    List<MethodInfo> findByClassInfoId(Long classId);

    List<MethodInfo> findByClassInfoIdIn(List<Long> classIds);
}
