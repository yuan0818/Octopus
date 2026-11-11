package io.yuan.octopus.scenario;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"io.yuan.octopus.scenario", "io.yuan.octopus.infra.neo4j"})
public class ScenarioRunnerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScenarioRunnerApplication.class, args);
    }
}
