package io.yuan.octopus.reconstruct;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "io.yuan.octopus.reconstruct",
    "io.yuan.octopus.infra.neo4j",
    "io.yuan.octopus.infra.common"
})
public class ReconstructApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconstructApplication.class, args);
    }
}
