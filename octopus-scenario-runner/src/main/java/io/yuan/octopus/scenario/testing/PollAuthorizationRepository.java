package io.yuan.octopus.scenario.testing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PollAuthorizationRepository extends JpaRepository<PollAuthorization, String> {
    List<PollAuthorization> findByProjectIdAndHttpMethodAndPathAndRevokedFalse(long projectId, String httpMethod, String path);

    List<PollAuthorization> findAllByOrderByUpdatedAtDesc();
}
