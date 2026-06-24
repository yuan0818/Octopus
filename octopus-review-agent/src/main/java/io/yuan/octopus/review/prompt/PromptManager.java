package io.yuan.octopus.review.prompt;

import io.yuan.octopus.review.entity.AgentPrompt;
import io.yuan.octopus.review.repository.AgentPromptRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);

    private final AgentPromptRepository repository;
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    public PromptManager(AgentPromptRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        List<AgentPrompt> all = repository.findAll();
        for (AgentPrompt p : all) {
            cache.put(p.getId(), new PromptTemplate(
                    p.getId(), p.getName(), p.getSystemPrompt(),
                    p.getUserPrompt(), p.getOutputSchema()));
        }
        if (cache.isEmpty()) {
            log.warn("No prompt templates found in DB. Ensure data.sql has been executed.");
        }
        log.info("PromptManager initialized: {} templates from DB", cache.size());
    }

    public PromptTemplate get(String id) {
        PromptTemplate template = cache.get(id);
        if (template == null) {
            throw new IllegalArgumentException("Prompt template not found: " + id);
        }
        return template;
    }

    @Transactional
    public void register(PromptTemplate template) {
        AgentPrompt entity = new AgentPrompt();
        entity.setId(template.id());
        entity.setName(template.name());
        entity.setSystemPrompt(template.systemPrompt());
        entity.setUserPrompt(template.userPrompt());
        entity.setOutputSchema(template.outputSchema());
        repository.save(entity);
        cache.put(template.id(), template);
        log.debug("Registered prompt template: {} ({})", template.id(), template.name());
    }

    public List<String> getTemplateIds() {
        return List.copyOf(cache.keySet());
    }

    @Transactional
    public void remove(String id) {
        cache.remove(id);
        repository.deleteById(id);
        log.debug("Removed prompt template: {}", id);
    }
}
