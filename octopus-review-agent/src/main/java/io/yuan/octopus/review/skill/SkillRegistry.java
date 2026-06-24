package io.yuan.octopus.review.skill;

import io.yuan.octopus.review.entity.AgentSkill;
import io.yuan.octopus.review.repository.AgentSkillRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final AgentSkillRepository repository;
    private final Map<String, Skill> cache = new LinkedHashMap<>();

    public SkillRegistry(AgentSkillRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        List<AgentSkill> all = repository.findAll();
        for (AgentSkill s : all) {
            cache.put(s.getId(), toSkill(s));
        }
        if (cache.isEmpty()) {
            log.warn("No skills found in DB. Ensure data.sql has been executed.");
        }
        log.info("SkillRegistry initialized: {} skills from DB", cache.size());
    }

    @Transactional
    public void register(Skill skill) {
        AgentSkill entity = new AgentSkill();
        entity.setId(skill.getId());
        entity.setName(skill.getName());
        entity.setDescription(skill.getDescription());
        entity.setPromptTemplateId(skill.getPromptTemplateId());
        entity.setPreferredProvider(skill.getPreferredProvider());
        entity.setOutputParser(skill.getOutputParser());
        entity.setMaxTokens(skill.getMaxTokens());
        entity.setEnabled(skill.isEnabled());
        repository.save(entity);
        cache.put(skill.getId(), skill);
        log.debug("Registered skill: {} ({})", skill.getId(), skill.getName());
    }

    public Skill get(String id) {
        Skill skill = cache.get(id);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + id
                    + ". Available: " + String.join(", ", cache.keySet()));
        }
        return skill;
    }

    public List<Skill> getEnabledSkills() {
        return cache.values().stream().filter(Skill::isEnabled).collect(Collectors.toList());
    }

    public Skill resolve(String skillId) {
        if (skillId != null && cache.containsKey(skillId)) return cache.get(skillId);
        Skill fallback = cache.get("code-review");
        if (fallback != null) return fallback;
        return cache.values().stream().findFirst().orElse(null);
    }

    @Transactional
    public void remove(String id) {
        cache.remove(id);
        repository.deleteById(id);
        log.debug("Removed skill: {}", id);
    }

    @Transactional
    public void toggle(String id) {
        repository.findById(id).ifPresent(entity -> {
            entity.setEnabled(!entity.isEnabled());
            repository.save(entity);
            Skill skill = cache.get(id);
            if (skill != null) {
                // Re-register with toggled enabled state
                cache.put(id, new Skill(id, skill.getName(), skill.getDescription(),
                        skill.getPromptTemplateId(), skill.getPreferredProvider(),
                        skill.getOutputParser(), skill.getMaxTokens(), entity.isEnabled()));
            }
            log.info("Toggled skill {}: enabled={}", id, entity.isEnabled());
        });
    }

    private Skill toSkill(AgentSkill entity) {
        return new Skill(entity.getId(), entity.getName(), entity.getDescription(),
                entity.getPromptTemplateId(), entity.getPreferredProvider(),
                entity.getOutputParser(), entity.getMaxTokens(), entity.isEnabled());
    }
}
