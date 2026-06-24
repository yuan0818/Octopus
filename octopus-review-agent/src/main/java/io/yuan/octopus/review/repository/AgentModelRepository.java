package io.yuan.octopus.review.repository;

import io.yuan.octopus.review.entity.AgentModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentModelRepository extends JpaRepository<AgentModel, String> {
    List<AgentModel> findByEnabledTrue();
    List<AgentModel> findByProvider(String provider);
}
