package io.yuan.octopus.review.model;

import io.yuan.octopus.review.entity.AgentModel;
import io.yuan.octopus.review.repository.AgentModelRepository;
import io.yuan.octopus.review.service.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 回归：管理接口脱敏曾原地改写缓存对象（Map.copyOf 是浅拷贝），
 * 导致"留空保存"把掩码 ******** 回写 DB、真实密钥被覆盖。
 * get / getAllConfigs 必须返回防御性副本。
 */
class ModelRegistryCacheIsolationTest {

    private ModelRegistry registry;
    private AgentModelRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(AgentModelRepository.class);
        SecretCipher cipher = new SecretCipher("0123456789abcdef0123456789abcdef");
        AgentModel entity = new AgentModel();
        entity.setName("m1");
        entity.setProvider("openai");
        entity.setModelId("x");
        entity.setApiUrl("http://x");
        entity.setApiKey(cipher.encrypt("sk-real"));
        entity.setEnabled(true);
        entity.setWeight(100);
        when(repository.findAll()).thenReturn(java.util.List.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        registry = new ModelRegistry(repository, cipher);
        registry.init();
    }

    @Test
    void getAllConfigsMutationMustNotAffectCache() {
        Map<String, ModelConfig> configs = registry.getAllConfigs();
        // 模拟管理接口脱敏：原地改写返回对象
        configs.values().forEach(c -> c.setApiKey("********"));
        assertEquals("sk-real", registry.get("m1").getApiKey());
    }

    @Test
    void getMutationMustNotAffectCache() {
        ModelConfig config = registry.get("m1");
        config.setApiKey("********");
        assertEquals("sk-real", registry.get("m1").getApiKey());
    }

    @Test
    void registeredKeyRoundTripsEncrypted() {
        ModelConfig config = new ModelConfig();
        config.setName("m2");
        config.setProvider("openai");
        config.setApiKey("sk-real");
        registry.register(config);
        verify(repository).save(argThat(e -> e.getApiKey() != null && e.getApiKey().startsWith("enc:v1:")));
    }
}
