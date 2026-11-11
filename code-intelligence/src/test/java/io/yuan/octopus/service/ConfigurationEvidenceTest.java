package io.yuan.octopus.service;
import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import com.github.javaparser.StaticJavaParser;
import static org.junit.jupiter.api.Assertions.*;
class ConfigurationEvidenceTest {
    @Test void projectsAllYamlDocumentsAndReferencesWithoutCredentialsOrDefaults() {
        String result=new Gson().toJson(ConfigurationEvidence.project("""
            spring:
              config:
                import: optional:nacos:shared.yaml?group=private-fixture
              profiles:
                active: dev
              cloud:
                nacos:
                  config:
                    server-addr: private-fixture:8848
                    username: private-fixture
                    password: ${CONFIG_PASSWORD:private-fixture}
                    shared-configs:
                      - data-id: common.yaml
                        refresh: true
            ---
            spring:
              config:
                activate:
                  on-profile: prod
            storage:
              url: jdbc:private-fixture
              key: ${SECRET:private-fixture}
            """,false));
        assertTrue(result.contains("common.yaml"));assertTrue(result.contains("shared.yaml"));assertTrue(result.contains("CONFIG_PASSWORD"));assertTrue(result.contains("containsDefault"));assertTrue(result.contains("prod"));assertTrue(result.contains("on-profile"));assertFalse(result.contains("private-fixture"));assertFalse(result.contains("jdbc:"));
    }
    @Test void propertiesLoadingAndBindingsPreserveKeysButHideValues() {
        String result=new Gson().toJson(ConfigurationEvidence.project("spring.config.import=classpath:defaults.properties\nspring.profiles.active=qa\ndatabase.password=${DB_SECRET:private-fixture}\nservice.endpoint=https://private-fixture\n",true));
        assertTrue(result.contains("defaults.properties"));assertTrue(result.contains("DB_SECRET"));assertFalse(result.contains("private-fixture"));
        String code=ModelSourceSanitizer.sanitizeRepository(StaticJavaParser.parse("""
            @PropertySource("classpath:defaults.properties")
            @ConditionalOnProperty(prefix="feature", name="enabled")
            class Config {
              @Value("${feature.value:private-fixture}") String value;
              String load(){return System.getProperty("feature.mode", "private-fixture");}
            }
            """));
        assertTrue(code.contains("classpath:defaults.properties"));assertTrue(code.contains("feature.mode"));assertTrue(code.contains("feature.value"));assertFalse(code.contains("private-fixture"));
    }
}
