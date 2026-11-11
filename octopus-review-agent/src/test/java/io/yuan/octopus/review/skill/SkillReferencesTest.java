package io.yuan.octopus.review.skill;

import tools.jackson.databind.ObjectMapper;
import io.yuan.octopus.review.entity.AgentSkill;
import io.yuan.octopus.review.prompt.*;
import io.yuan.octopus.review.repository.AgentSkillRepository;
import io.yuan.octopus.review.service.PromptBuilder;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SkillReferencesTest {
    private Skill skill(List<SkillPackage.SkillReference> refs) {
        return new Skill("review", "审查", "summary", "prompt", null, "auto", 8000, true,
                "## 检查顺序\n\n1. 先核对契约\n2. 再验证错误分支", refs);
    }
    private List<SkillPackage.SkillReference> refs() {
        return List.of(new SkillPackage.SkillReference("接口规范 / v2", "## 请求约定\n\n必须检查 `X-Sign`。\n\n```json\n{\"ok\":true}\n```", "markdown"));
    }

    @Test
    void referencesPersistReloadToggleAndCanBeRemovedWithoutTouchingTemplate() {
        var repo = mock(AgentSkillRepository.class);
        Map<String, AgentSkill> rows = new HashMap<>();
        when(repo.save(any())).thenAnswer(i -> { AgentSkill row = i.getArgument(0); rows.put(row.getId(), row); return row; });
        when(repo.findAll()).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        var registry = new SkillRegistry(repo);
        registry.register(skill(refs()));
        var restarted = new SkillRegistry(repo);
        restarted.init();
        assertEquals(refs(), restarted.get("review").getReferences());
        assertEquals(skill(refs()).getInstructions(), restarted.get("review").getInstructions());
        restarted.toggle("review");
        assertFalse(restarted.get("review").isEnabled());
        assertEquals(refs(), restarted.get("review").getReferences());
        restarted.register(skill(List.of()));
        restarted.init();
        assertTrue(restarted.get("review").getReferences().isEmpty());
    }

    @Test
    void promptReceivesInstructionsAndReferencesExactlyOnceWithoutMutatingSharedTemplate() {
        var manager = mock(PromptManager.class);
        var template = new PromptTemplate("prompt", "Review", "基础提示", "{{sourceCode}}", "{\"issues\":[]}");
        when(manager.get("prompt")).thenReturn(template);
        var builder = new PromptBuilder(manager);
        var rendered = builder.getSystemPrompt(skill(refs()));
        assertTrue(rendered.contains("检查顺序"));
        assertTrue(rendered.contains("接口规范 / v2"));
        assertEquals(1, rendered.split("X-Sign", -1).length - 1);
        assertTrue(rendered.contains("{\"issues\":[]}"));
        var legacy = new Skill("legacy", "旧 Skill", "", "prompt", "deepseek", "auto", 8000, true);
        assertFalse(builder.getSystemPrompt(legacy).contains("X-Sign"));
        assertEquals("基础提示", template.systemPrompt());
    }

    @Test
    void zipRoundTripPreservesReferenceTitlesAndKeepsBasePromptSeparate() {
        var registry = mock(SkillRegistry.class);
        var manager = mock(PromptManager.class);
        when(registry.get("review")).thenReturn(skill(refs()));
        when(manager.get("prompt")).thenReturn(new PromptTemplate("prompt", "p", "base", "{{anchor}}", "{}"));
        var service = new SkillPackageService(registry, manager);
        var result = service.importBytes(service.exportZip("review"));
        assertTrue(result.success());
        var saved = org.mockito.ArgumentCaptor.forClass(Skill.class);
        verify(registry).register(saved.capture());
        assertEquals(refs(), saved.getValue().getReferences());
        assertEquals(skill(refs()).getInstructions(), saved.getValue().getInstructions());
        verify(manager).register(argThat(p -> p.systemPrompt().equals("base") && p.userPrompt().equals("{{anchor}}")));
    }

    @Test
    void jsonSupportsBothNewContentAndLegacyRequests() throws Exception {
        var mapper = new ObjectMapper();
        Skill roundTrip = mapper.readValue(mapper.writeValueAsString(skill(refs())), Skill.class);
        assertEquals(refs(), roundTrip.getReferences());
        Skill legacy = mapper.readValue("{\"id\":\"old\",\"name\":\"old\",\"maxTokens\":8000,\"enabled\":true}", Skill.class);
        assertTrue(legacy.getReferences().isEmpty());
        assertEquals("", legacy.getInstructions());
    }

    @Test
    void invalidReferenceDocumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> skill(List.of(refs().getFirst(), refs().getFirst())));
        assertThrows(IllegalArgumentException.class, () -> skill(List.of(new SkillPackage.SkillReference("empty", " ", "markdown"))));
        assertThrows(IllegalArgumentException.class, () -> skill(List.of(new SkillPackage.SkillReference("large", "a".repeat(50001), "markdown"))));
    }
}
