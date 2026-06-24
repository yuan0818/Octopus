package io.yuan.octopus.review.service;

import io.yuan.octopus.review.dto.RequirementItem;
import io.yuan.octopus.review.dto.ReviewMode;
import io.yuan.octopus.review.entity.AgentPrompt;
import io.yuan.octopus.review.prompt.PromptManager;
import io.yuan.octopus.review.repository.AgentPromptRepository;
import io.yuan.octopus.review.skill.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptBuilderTest {

    private PromptBuilder builder;
    private Skill codeReviewSkill;

    @BeforeEach
    void setUp() {
        AgentPromptRepository repository = mock(AgentPromptRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                prompt("review.code", "代码审查", "代码审查系统提示", """
                        入口: {{anchor}}
                        项目上下文: {{projectContext}}
                        需求:
                        {{requirements}}
                        源码:
                        {{sourceCode}}
                        """, "{\"requirementChecks\":[]}"),
                prompt("review.security", "安全审查", "安全审查系统提示", """
                        入口: {{anchor}}
                        项目上下文: {{projectContext}}
                        需求:
                        {{requirements}}
                        源码:
                        {{sourceCode}}
                        """, "{\"securityFindings\":[]}")
        ));
        PromptManager promptManager = new PromptManager(repository);
        promptManager.init();
        builder = new PromptBuilder(promptManager);
        // code-review skill is registered in PromptManager.init()
        codeReviewSkill = new Skill("code-review", "测试", "test",
                "review.code", "claude", "requirement-check", 128000, true);
    }

    @Test
    void buildContainsAllSections() {
        String projectContext = "★ [ESTABLISHED] 使用 @Slf4j";
        Map<String, Object> chainMetadata = new LinkedHashMap<>();
        chainMetadata.put("anchor", "com.x.Controller.create");
        chainMetadata.put("totalClasses", 3);

        List<RequirementItem> requirements = List.of(
                new RequirementItem("REQ-001", "校验", "使用 @Valid")
        );

        Map<String, String> sourceCode = new LinkedHashMap<>();
        sourceCode.put("com.x.Controller", "package com.x;\n@RestController\npublic class Controller {}");

        String prompt = builder.build(codeReviewSkill, projectContext, chainMetadata, requirements, sourceCode);

        assertTrue(prompt.contains("com.x.Controller.create"));
        assertTrue(prompt.contains("REQ-001"));
        assertTrue(prompt.contains("@Slf4j"));
        assertTrue(prompt.contains("@RestController"));
    }

    @Test
    void buildWithEmptyProjectContext() {
        Map<String, Object> chainMetadata = new LinkedHashMap<>();
        chainMetadata.put("anchor", "test");
        chainMetadata.put("totalClasses", 1);

        String prompt = builder.build(codeReviewSkill, "",
                chainMetadata,
                List.of(new RequirementItem("REQ-001", "T", "D")),
                Map.of("com.x.Test", "class Test {}"));

        assertTrue(prompt.contains("REQ-001"));
        assertTrue(prompt.contains("class Test"));
    }

    @Test
    void buildRiskBasedPromptWithoutRequirements() {
        Map<String, Object> chainMetadata = new LinkedHashMap<>();
        chainMetadata.put("anchor", "com.x.Job.run");

        String prompt = builder.build(codeReviewSkill, "",
                chainMetadata,
                List.of(),
                Map.of("com.x.Job", "class Job { void run() {} }"),
                ReviewMode.RISK_BASED);

        assertTrue(prompt.contains("RISK_BASED"));
        assertTrue(prompt.contains("禁止输出 REQUIREMENT_MISMATCH"));
        assertTrue(prompt.contains("不判断需求覆盖"));
    }

    @Test
    void systemPromptContainsOutputSchema() {
        String system = builder.getSystemPrompt(codeReviewSkill);
        assertNotNull(system);
        assertTrue(system.contains("requirementChecks"));
    }

    @Test
    void differentSkillsHaveDifferentSystemPrompts() {
        Skill securitySkill = new Skill("security-review", "安全", "security",
                "review.security", "claude", "security-finding", 64000, true);

        String codeSystem = builder.getSystemPrompt(codeReviewSkill);
        String securitySystem = builder.getSystemPrompt(securitySkill);

        assertNotEquals(codeSystem, securitySystem);
        assertTrue(securitySystem.contains("安全"));
    }

    private AgentPrompt prompt(String id, String name, String systemPrompt, String userPrompt, String outputSchema) {
        AgentPrompt prompt = new AgentPrompt();
        prompt.setId(id);
        prompt.setName(name);
        prompt.setSystemPrompt(systemPrompt);
        prompt.setUserPrompt(userPrompt);
        prompt.setOutputSchema(outputSchema);
        return prompt;
    }
}
