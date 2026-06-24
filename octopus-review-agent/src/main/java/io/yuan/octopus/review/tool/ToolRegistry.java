package io.yuan.octopus.review.tool;

import io.yuan.octopus.review.entity.AgentTool;
import io.yuan.octopus.review.repository.AgentToolRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final AgentToolRepository repository;
    private final Map<String, Tool> cache = new ConcurrentHashMap<>();

    public ToolRegistry(AgentToolRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        List<AgentTool> all = repository.findAll();
        for (AgentTool t : all) {
            cache.put(t.getId(), toTool(t));
        }
        if (cache.isEmpty()) {
            log.warn("No tools found in DB. Ensure data.sql has been executed.");
        }
        log.info("ToolRegistry initialized: {} tools from DB", cache.size());
    }

    public List<Tool> getAll() { return List.copyOf(cache.values()); }

    public List<Tool> getEnabled() {
        return cache.values().stream().filter(Tool::isEnabled).toList();
    }

    public Tool get(String id) {
        Tool tool = cache.get(id);
        if (tool == null) throw new IllegalArgumentException("Tool not found: " + id);
        return tool;
    }

    @Transactional
    public void register(Tool tool) {
        AgentTool entity = new AgentTool();
        entity.setId(tool.getId());
        entity.setName(tool.getName());
        entity.setDescription(tool.getDescription());
        entity.setParameters(tool.getParameters());
        entity.setEnabled(tool.isEnabled());
        repository.save(entity);
        cache.put(tool.getId(), tool);
        log.debug("Registered tool: {} ({})", tool.getId(), tool.getName());
    }

    @Transactional
    public void remove(String id) {
        cache.remove(id);
        repository.deleteById(id);
        log.debug("Removed tool: {}", id);
    }

    @Transactional
    public void toggle(String id) {
        repository.findById(id).ifPresent(entity -> {
            entity.setEnabled(!entity.isEnabled());
            repository.save(entity);
            Tool tool = cache.get(id);
            if (tool != null) tool.setEnabled(entity.isEnabled());
            log.info("Toggled tool {}: enabled={}", id, entity.isEnabled());
        });
    }

    private Tool toTool(AgentTool entity) {
        Tool tool = new Tool();
        tool.setId(entity.getId());
        tool.setName(entity.getName());
        tool.setDescription(entity.getDescription());
        tool.setParameters(entity.getParameters());
        tool.setEnabled(entity.isEnabled());
        return tool;
    }
}
