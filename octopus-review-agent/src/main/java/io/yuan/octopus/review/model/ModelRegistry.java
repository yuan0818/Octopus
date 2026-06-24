package io.yuan.octopus.review.model;

import io.yuan.octopus.review.entity.AgentModel;
import io.yuan.octopus.review.repository.AgentModelRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Central registry for LLM model configurations, backed by MySQL.
 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final AgentModelRepository repository;
    private final Map<String, ModelConfig> cache = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> stats = new ConcurrentHashMap<>();
    private final Map<String, List<String>> fallbackChains = new ConcurrentHashMap<>();

    public ModelRegistry(AgentModelRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        List<AgentModel> all = repository.findAll();
        for (AgentModel entity : all) {
            cache.put(entity.getName(), toConfig(entity));
            stats.put(entity.getName(), new AtomicInteger(0));
        }
        // Build fallback chains per provider
        all.stream().filter(AgentModel::isEnabled)
                .collect(Collectors.groupingBy(AgentModel::getProvider))
                .forEach((provider, models) -> {
                    models.sort(Comparator.comparingInt(AgentModel::getWeight).reversed());
                    fallbackChains.put(provider, models.stream().map(AgentModel::getName).toList());
                });
        log.info("ModelRegistry initialized: {} models from DB", cache.size());
    }

    @Transactional
    public void register(ModelConfig config) {
        AgentModel entity = new AgentModel();
        entity.setName(config.getName());
        entity.setProvider(config.getProvider());
        entity.setModelId(config.getModelId());
        entity.setApiUrl(config.getApiUrl());
        entity.setApiKey(config.getApiKey());
        entity.setMaxTokens(config.getMaxTokens());
        entity.setTemperature(config.getTemperature());
        entity.setEnabled(config.isEnabled());
        entity.setWeight(config.getWeight());
        repository.save(entity);
        cache.put(config.getName(), config);
        stats.put(config.getName(), new AtomicInteger(0));
        rebuildFallbackChains();
        log.debug("Registered model: {} ({})", config.getName(), config.getModelId());
    }

    public ModelConfig get(String name) {
        return cache.get(name);
    }

    public ModelConfig select(String provider, String taskType) {
        ModelConfig exact = get(provider);
        if (exact != null && exact.isEnabled()) return exact;
        return cache.values().stream()
                .filter(m -> m.getProvider().equals(provider) && m.isEnabled())
                .findFirst().orElse(null);
    }

    public ModelConfig selectWithFallback(String provider) {
        List<String> chain = fallbackChains.get(provider);
        if (chain == null) return select(provider, "general");
        for (String name : chain) {
            ModelConfig config = get(name);
            if (config != null && config.isEnabled()) return config;
        }
        return null;
    }

    public String getApiKey(String provider) {
        return cache.values().stream()
                .filter(m -> m.getProvider().equals(provider))
                .findFirst()
                .map(ModelConfig::getApiKey)
                .orElse(null);
    }

    @Transactional
    public void setApiKey(String provider, String apiKey) {
        List<AgentModel> models = repository.findByProvider(provider);
        for (AgentModel m : models) {
            m.setApiKey(apiKey);
            repository.save(m);
        }
        // Update cache
        cache.values().stream()
                .filter(c -> c.getProvider().equals(provider))
                .forEach(c -> c.setApiKey(apiKey));
        log.info("Updated API key for provider: {}", provider);
    }

    public Map<String, String> getAllApiKeys() {
        Map<String, String> result = new LinkedHashMap<>();
        cache.values().forEach(c -> {
            if (c.getApiKey() != null && !c.getApiKey().isBlank()) {
                result.putIfAbsent(c.getProvider(), c.getApiKey());
            }
        });
        return result;
    }

    @Transactional
    public void remove(String name) {
        cache.remove(name);
        stats.remove(name);
        repository.deleteById(name);
        rebuildFallbackChains();
        log.info("Removed model: {}", name);
    }

    @Transactional
    public void toggle(String name) {
        repository.findById(name).ifPresent(entity -> {
            entity.setEnabled(!entity.isEnabled());
            repository.save(entity);
            ModelConfig config = cache.get(name);
            if (config != null) config.setEnabled(entity.isEnabled());
            rebuildFallbackChains();
            log.info("Toggled model {}: enabled={}", name, entity.isEnabled());
        });
    }

    public void recordSuccess(String modelName) {
        stats.computeIfAbsent(modelName, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void recordFailure(String modelName) {
        log.warn("Model {} recorded a failure", modelName);
    }

    public void setFallbackChain(String provider, List<String> modelNames) {
        fallbackChains.put(provider, modelNames);
    }

    public Set<String> getAvailableModels() {
        return cache.values().stream()
                .filter(ModelConfig::isEnabled)
                .map(ModelConfig::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Map<String, ModelConfig> getAllConfigs() {
        return Map.copyOf(cache);
    }

    private void rebuildFallbackChains() {
        fallbackChains.clear();
        cache.values().stream().filter(ModelConfig::isEnabled)
                .collect(Collectors.groupingBy(ModelConfig::getProvider))
                .forEach((provider, configs) -> {
                    configs.sort(Comparator.comparingInt(ModelConfig::getWeight).reversed());
                    fallbackChains.put(provider, configs.stream().map(ModelConfig::getName).toList());
                });
    }

    private ModelConfig toConfig(AgentModel entity) {
        ModelConfig config = new ModelConfig();
        config.setName(entity.getName());
        config.setProvider(entity.getProvider());
        config.setModelId(entity.getModelId());
        config.setApiUrl(entity.getApiUrl());
        config.setApiKey(entity.getApiKey());
        config.setMaxTokens(entity.getMaxTokens());
        config.setTemperature(entity.getTemperature());
        config.setEnabled(entity.isEnabled());
        config.setWeight(entity.getWeight());
        return config;
    }
}
