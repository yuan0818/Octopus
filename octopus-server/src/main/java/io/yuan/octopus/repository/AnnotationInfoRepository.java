package io.yuan.octopus.repository;

import io.yuan.octopus.entity.AnnotationInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnnotationInfoRepository extends JpaRepository<AnnotationInfo, Long> {

    void deleteByTargetTypeAndTargetIdIn(String targetType, List<Long> targetIds);

    List<AnnotationInfo> findByAnnotationName(String annotationName);

    List<AnnotationInfo> findByAnnotationNameIn(List<String> annotationNames);

    List<AnnotationInfo> findByTargetTypeAndTargetId(String targetType, Long targetId);

    List<AnnotationInfo> findByAnnotationNameInAndTargetTypeAndTargetIdIn(
            List<String> annotationNames, String targetType, List<Long> targetIds);
}
