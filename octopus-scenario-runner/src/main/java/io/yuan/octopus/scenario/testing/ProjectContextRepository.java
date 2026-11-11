package io.yuan.octopus.scenario.testing;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ProjectContextRepository extends JpaRepository<ProjectContext, String> {
    List<ProjectContext> findByRepositoryKey(String repositoryKey);
}
