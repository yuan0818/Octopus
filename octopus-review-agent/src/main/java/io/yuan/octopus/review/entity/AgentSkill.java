package io.yuan.octopus.review.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_skill")
public class AgentSkill {

    @Id
    @Column(name = "id", length = 100)
    private String id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "prompt_template_id", length = 100)
    private String promptTemplateId;

    @Column(name = "preferred_provider", length = 50)
    private String preferredProvider;

    @Column(name = "output_parser", length = 50)
    private String outputParser;

    @Column(name = "max_tokens")
    private int maxTokens = 128000;

    @Column(name = "enabled")
    private boolean enabled = true;

    @Column(name = "instructions", columnDefinition = "LONGTEXT")
    private String instructions;

    @Column(name = "references_json", columnDefinition = "LONGTEXT")
    private String referencesJson;

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public String getReferencesJson() { return referencesJson; }
    public void setReferencesJson(String referencesJson) { this.referencesJson = referencesJson; }

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPromptTemplateId() { return promptTemplateId; }
    public void setPromptTemplateId(String promptTemplateId) { this.promptTemplateId = promptTemplateId; }
    public String getPreferredProvider() { return preferredProvider; }
    public void setPreferredProvider(String preferredProvider) { this.preferredProvider = preferredProvider; }
    public String getOutputParser() { return outputParser; }
    public void setOutputParser(String outputParser) { this.outputParser = outputParser; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
