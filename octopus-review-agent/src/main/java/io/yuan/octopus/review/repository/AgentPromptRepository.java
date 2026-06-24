package io.yuan.octopus.review.repository;

import io.yuan.octopus.review.entity.AgentPrompt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentPromptRepository extends JpaRepository<AgentPrompt, String> {
}
