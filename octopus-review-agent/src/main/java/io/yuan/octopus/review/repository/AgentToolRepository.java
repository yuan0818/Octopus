package io.yuan.octopus.review.repository;

import io.yuan.octopus.review.entity.AgentTool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentToolRepository extends JpaRepository<AgentTool, String> {
    List<AgentTool> findByEnabledTrue();
}
