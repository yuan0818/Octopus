package io.yuan.octopus.scenario.testing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegressionBatchRepository extends JpaRepository<RegressionBatch, String> {
    List<RegressionBatch> findTop100ByOrderByCreatedAtDesc();

    List<RegressionBatch> findByStatus(String status);

    List<RegressionBatch> findByCallbackStatus(String callbackStatus);

    Optional<RegressionBatch> findByIdempotencyKey(String idempotencyKey);
}
