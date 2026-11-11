package io.yuan.octopus.scenario.testing;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface RepositoryAnalysisRepository extends JpaRepository<RepositoryAnalysisWorkspace,String> {
    @Query("select r.id from RepositoryAnalysisWorkspace r") List<String> scopeIds();
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from RepositoryAnalysisWorkspace r where r.id=:id") Optional<RepositoryAnalysisWorkspace> locked(String id);
    @Query("select r.id from RepositoryAnalysisWorkspace r where r.state in ('QUEUED','COLLECTING','PLANNING','ANALYZING','SYNTHESIZING','SUMMARIZING')") List<String> pending();
}
