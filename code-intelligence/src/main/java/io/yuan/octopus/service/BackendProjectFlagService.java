package io.yuan.octopus.service;

import io.yuan.octopus.entity.ClassInfo;
import io.yuan.octopus.entity.AnalyzedProjectEntity;
import io.yuan.octopus.repository.AnnotationInfoRepository;
import io.yuan.octopus.repository.ClassInfoRepository;
import io.yuan.octopus.repository.AnalyzedProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Determines whether a project is a backend / API service by inspecting its
 * stored class-level annotations ({@code @RestController}, {@code @Controller}).
 * <p>
 * Extracted from {@link AnalysisOrchestrationService} to reduce its constructor
 * parameter count and improve separation of concerns.
 */
@Service
public class BackendProjectFlagService {

    private static final Logger log = LoggerFactory.getLogger(BackendProjectFlagService.class);

    private final ClassInfoRepository classInfoRepository;
    private final AnnotationInfoRepository annotationInfoRepository;
    private final AnalyzedProjectRepository javaProjectRepository;

    public BackendProjectFlagService(ClassInfoRepository classInfoRepository,
                                     AnnotationInfoRepository annotationInfoRepository,
                                     AnalyzedProjectRepository javaProjectRepository) {
        this.classInfoRepository = classInfoRepository;
        this.annotationInfoRepository = annotationInfoRepository;
        this.javaProjectRepository = javaProjectRepository;
    }

    /**
     * Updates the {@code isBackendProject} and {@code isApiService} flags on the
     * given project entity by checking whether any HTTP endpoint annotations exist
     * in the stored class metadata.  Always writes either {@code true} or
     * {@code false} to overwrite stale values.
     */
    public void updateBackendProjectFlag(AnalyzedProjectEntity projectEntity) {
        List<Long> classIds = classInfoRepository.findByProjectId(projectEntity.getId())
                .stream().map(ClassInfo::getId)
                .toList();

        // All projects processed by octopus are JVM/backend projects.
        projectEntity.setIsBackendProject(true);

        // Detect whether the project exposes HTTP API endpoints (has @RestController / @Controller).
        boolean isApiService = false;
        if (!classIds.isEmpty()) {
            List<String> controllerAnnotations = List.of("RestController", "Controller");
            isApiService = !annotationInfoRepository
                    .findByAnnotationNameInAndTargetTypeAndTargetIdIn(controllerAnnotations, "CLASS", classIds)
                    .isEmpty();
        }
        projectEntity.setIsApiService(isApiService);

        javaProjectRepository.save(projectEntity);
        log.debug("Project '{}' isBackendProject=true, isApiService={}",
                projectEntity.getProjectName(), isApiService);
    }
}
