package io.yuan.octopus.review.skill;

import java.util.List;
import java.util.Map;

/**
 * A SkillPackage is a self-contained, versioned skill definition
 * that can be imported from a ZIP archive.
 * <p>
 * Unlike a simple Skill record (which just references a prompt template ID),
 * a SkillPackage carries its own templates, reference materials, examples,
 * and model preferences — everything needed to execute the skill.
 */
public class SkillPackage {

    private final String id;
    private final String name;
    private final String description;
    private final String version;
    private final String author;

    // Self-contained templates
    private final String systemPrompt;
    private final String userPromptTemplate;
    private final String outputSchema;

    // Reference materials injected into the prompt context
    private final List<SkillReference> references;

    // Examples for few-shot prompting
    private final List<SkillExample> examples;

    // Model configuration
    private final String preferredProvider;
    private final int maxTokens;
    private final Map<String, Object> metadata;

    public SkillPackage(String id, String name, String description, String version,
                        String author, String systemPrompt, String userPromptTemplate,
                        String outputSchema, List<SkillReference> references,
                        List<SkillExample> examples, String preferredProvider,
                        int maxTokens, Map<String, Object> metadata) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.version = version;
        this.author = author;
        this.systemPrompt = systemPrompt;
        this.userPromptTemplate = userPromptTemplate;
        this.outputSchema = outputSchema;
        this.references = references;
        this.examples = examples;
        this.preferredProvider = preferredProvider;
        this.maxTokens = maxTokens;
        this.metadata = metadata;
    }

    /** Convert to a standard Skill for execution. */
    public Skill toSkill() {
        String promptId = "pkg:" + id + "@" + version;
        return new Skill(id, name, description, promptId, preferredProvider, "auto", maxTokens, true);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public String getAuthor() { return author; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getUserPromptTemplate() { return userPromptTemplate; }
    public String getOutputSchema() { return outputSchema; }
    public List<SkillReference> getReferences() { return references; }
    public List<SkillExample> getExamples() { return examples; }
    public String getPreferredProvider() { return preferredProvider; }
    public int getMaxTokens() { return maxTokens; }
    public Map<String, Object> getMetadata() { return metadata; }

    /** A reference document injected into the prompt context. */
    public record SkillReference(String title, String content, String type) {}

    /** An example (few-shot) for the skill. */
    public record SkillExample(String input, String output, String description) {}

    /** Build a formatted references section for prompt injection. */
    public String formatReferencesForPrompt() {
        if (references == null || references.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n## Reference Materials\n");
        for (SkillReference ref : references) {
            sb.append("### ").append(ref.title()).append("\n");
            sb.append(ref.content()).append("\n\n");
        }
        return sb.toString();
    }

    /** Build a formatted examples section for few-shot prompting. */
    public String formatExamplesForPrompt() {
        if (examples == null || examples.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n## Examples\n");
        for (SkillExample ex : examples) {
            if (ex.description() != null) sb.append("### ").append(ex.description()).append("\n");
            sb.append("Input:\n").append(ex.input()).append("\n");
            sb.append("Output:\n").append(ex.output()).append("\n\n");
        }
        return sb.toString();
    }
}
