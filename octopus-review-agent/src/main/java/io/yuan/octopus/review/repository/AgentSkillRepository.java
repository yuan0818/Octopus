package io.yuan.octopus.review.repository;

import io.yuan.octopus.review.entity.AgentSkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentSkillRepository extends JpaRepository<AgentSkill, String> {
    List<AgentSkill> findByEnabledTrue();
}
