package io.yuan.octopus.scenario.testing;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface RepositoryAnalysisHistoryRepository extends JpaRepository<RepositoryAnalysisHistory,String> {
    interface Metadata { String getId(); String getScopeId(); boolean getCurrent(); String getSummaryEncrypted(); }
    @Query("select r.id as id,r.scopeId as scopeId,r.current as current,r.summaryEncrypted as summaryEncrypted from RepositoryAnalysisHistory r order by r.updatedAt desc,r.id desc") List<Metadata> metadata();
}
