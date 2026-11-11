package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.yuan.octopus.scenario.testing.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** 真实 Spring 容器装配全部新组件（构造器选择、可选依赖、仓库），并经服务层跑通一个持久化的静态任务。 */
class CaseGenerationWiringTest {
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {CaseGenerationTaskEntity.class, BusinessTestRun.class})
    @EnableJpaRepositories(basePackageClasses = {CaseGenerationTaskRepository.class, BusinessTestRunRepository.class})
    @Import({StaticChecks.class, CaseGenerationService.class, CaseGenerationStore.class, CaseGenerationController.class, ProbeExecutionService.class,
            ProbeLeaseSupervisor.class, ProbeEnvironmentService.class, ContextCredentialCipher.class, CaseCandidateService.class,
            NegativeCaseSuggestionService.class, ScenarioPlanner.class, IrValidator.class, CapabilityRegistry.class, JmxCompiler.class, JmeterProcessExecutor.class})
    static class Config {
        @Bean BusinessKnowledgeStore knowledge() { return GenerationFixtures.store(GenerationFixtures.flow(false)); }
        @Bean FlowModelClient models() { return mock(FlowModelClient.class); }
        @Bean SuggestionMetricsService metrics() { return mock(SuggestionMetricsService.class); }
    }

    @Test
    void containerWiresEveryComponentAndRunsAPersistedStaticTask() throws Exception {
        try (var app = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=jdbc:h2:mem:wiring_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.sql.init.mode=never",
                "--scenario.testing.encryption-key=0123456789abcdef0123456789abcdef",
                "--logging.level.root=ERROR", "--spring.main.banner-mode=off")) {
            var service = app.getBean(CaseGenerationService.class);
            var created = service.create(GenerationFixtures.request(List.of(Strategy.RULE), ProbeMode.OFF, new Rejection(422, "错误码规范 §2")), "tester");
            CaseGenerationService.TaskView view = created;
            for (int i = 0; i < 200 && !CaseGenerationEngine.terminal(TaskState.valueOf(view.state())); i++) { Thread.sleep(50); view = service.get(created.id()); }
            assertTrue(CaseGenerationEngine.terminal(TaskState.valueOf(view.state())), view.state() + " " + view.error());
            assertEquals("CONVERGED", view.endReason());
            assertFalse(view.candidates().isEmpty());
            assertTrue(view.candidates().stream().allMatch(c -> "NOT_PROBED".equals(c.conclusion()) || "STATIC_INVALID".equals(c.conclusion())));
            assertEquals(0, app.getBean(ProbeRunRepository.class).count(), "probe OFF sends nothing");
            assertNotNull(app.getBean(CaseGenerationController.class));
            service.close();
        }
    }
}
