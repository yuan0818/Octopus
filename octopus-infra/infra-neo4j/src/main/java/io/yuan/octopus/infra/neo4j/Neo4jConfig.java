package io.yuan.octopus.infra.neo4j;

import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Creates the Neo4j {@link Driver} bean from {@code octopus.neo4j.*} properties.
 */
@Configuration
public class Neo4jConfig {

    @Value("${octopus.neo4j.uri}")
    private String uri;

    @Value("${octopus.neo4j.username}")
    private String username;

    @Value("${octopus.neo4j.password}")
    private String password;

    private Driver driver;

    @Bean
    public Driver neo4jDriver() {
        Config config = Config.builder()
                .withConnectionTimeout(10, TimeUnit.SECONDS)
                .withMaxConnectionPoolSize(10)
                .withConnectionAcquisitionTimeout(10, TimeUnit.SECONDS)
                .build();
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
        return this.driver;
    }

    @PreDestroy
    public void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }
}
