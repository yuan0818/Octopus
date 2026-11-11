package io.yuan.octopus.review.skill;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.review.prompt.PromptManager;
import io.yuan.octopus.review.prompt.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Imports and exports SkillPackage from/to ZIP archives.
 * <p>
 * ZIP structure:
 * <pre>
 * skill-package/
 *   skill.json              # Package metadata
 *   prompts/
 *     system.prompt.md      # System prompt
 *     user.prompt.md        # User prompt template
 *     output-schema.json    # Output format specification
 *   references/
 *     *.md                  # Reference documents
 *   examples/
 *     example-01.json       # Few-shot examples
 * </pre>
 */
@Service
public class SkillPackageService {

    private static final Logger log = LoggerFactory.getLogger(SkillPackageService.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final SkillRegistry skillRegistry;
    private final PromptManager promptManager;

    public SkillPackageService(SkillRegistry skillRegistry, PromptManager promptManager) {
        this.skillRegistry = skillRegistry;
        this.promptManager = promptManager;
    }

    /**
     * Import a skill from a ZIP archive.
     * Registers the skill and its prompts in the system.
     */
    public ImportResult importZip(InputStream zipInput) {
        try (ZipInputStream zis = new ZipInputStream(zipInput)) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zis.readAllBytes());
                }
                zis.closeEntry();
            }
            return processEntries(entries);
        } catch (IOException e) {
            return new ImportResult(false, "Failed to read ZIP: " + e.getMessage(), null, List.of());
        }
    }

    /**
     * Import a skill from a raw ZIP byte array.
     */
    public ImportResult importBytes(byte[] zipData) {
        return importZip(new ByteArrayInputStream(zipData));
    }

    /**
     * Export a skill as a ZIP archive.
     */
    public byte[] exportZip(String skillId) {
        Skill skill = skillRegistry.get(skillId);
        PromptTemplate prompt = promptManager.get(skill.getPromptTemplateId());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {

            // skill.json
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("id", skill.getId());
            manifest.put("name", skill.getName());
            manifest.put("description", skill.getDescription());
            manifest.put("version", "1.0");
            manifest.put("maxTokens", skill.getMaxTokens());
            Map<String, String> referenceTitles = new LinkedHashMap<>();
            for (int i = 0; i < skill.getReferences().size(); i++) {
                var reference = skill.getReferences().get(i);
                String path = "references/ref-" + (i + 1) + ".md";
                referenceTitles.put(path, reference.title());
                addEntry(zos, path, reference.content());
            }
            manifest.put("referenceTitles", referenceTitles);
            addEntry(zos, "skill.json", gson.toJson(manifest));
            addEntry(zos, "SKILL.md", skill.getInstructions());

            // prompts
            addEntry(zos, "prompts/system.prompt.md", prompt.systemPrompt());
            addEntry(zos, "prompts/user.prompt.md", prompt.userPrompt());
            addEntry(zos, "prompts/output-schema.json", prompt.outputSchema());

        } catch (IOException e) {
            throw new RuntimeException("Failed to export skill: " + e.getMessage(), e);
        }
        return baos.toByteArray();
    }

    // ── Internal processing ──

    private ImportResult processEntries(Map<String, byte[]> entries) {
        List<String> warnings = new ArrayList<>();

        // Parse manifest
        byte[] manifestBytes = entries.get("skill.json");
        if (manifestBytes == null) {
            return new ImportResult(false, "ZIP must contain skill.json", null, List.of());
        }

        Map<String, Object> manifest;
        try {
            manifest = gson.fromJson(new String(manifestBytes, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() {}.getType());
        } catch (Exception e) {
            return new ImportResult(false, "Failed to parse skill.json: " + e.getMessage(), null, List.of());
        }

        String id = (String) manifest.getOrDefault("id", UUID.randomUUID().toString());
        String name = (String) manifest.getOrDefault("name", id);
        String description = (String) manifest.getOrDefault("description", "");
        String version = (String) manifest.getOrDefault("version", "1.0");
        String provider = (String) manifest.get("preferredProvider");
        int maxTokens = manifest.get("maxTokens") instanceof Number n ? n.intValue() : 128000;

        // Extract prompts
        String systemPrompt = getEntryContent(entries, "prompts/system.prompt.md");
        String userPrompt = getEntryContent(entries, "prompts/user.prompt.md");
        String outputSchema = getEntryContent(entries, "prompts/output-schema.json");

        if (systemPrompt == null) {
            return new ImportResult(false, "Missing prompts/system.prompt.md", null, List.of());
        }
        if (userPrompt == null) {
            return new ImportResult(false, "Missing prompts/user.prompt.md", null, List.of());
        }

        // Extract references
        List<SkillPackage.SkillReference> references = new ArrayList<>();
        for (var entry : entries.entrySet()) {
            if (entry.getKey().startsWith("references/") && entry.getKey().endsWith(".md")) {
                String title = entry.getKey().replace("references/", "").replace(".md", "");
                if (manifest.get("referenceTitles") instanceof Map<?, ?> titles && titles.get(entry.getKey()) instanceof String originalTitle)
                    title = originalTitle;
                String content = new String(entry.getValue(), StandardCharsets.UTF_8);
                references.add(new SkillPackage.SkillReference(title, content, "markdown"));
            }
        }

        // Extract examples
        List<SkillPackage.SkillExample> examples = new ArrayList<>();
        for (var entry : entries.entrySet()) {
            if (entry.getKey().startsWith("examples/") && entry.getKey().endsWith(".json")) {
                try {
                    Map<String, Object> ex = gson.fromJson(
                            new String(entry.getValue(), StandardCharsets.UTF_8),
                            new TypeToken<Map<String, Object>>() {}.getType());
                    examples.add(new SkillPackage.SkillExample(
                            (String) ex.getOrDefault("input", ""),
                            (String) ex.getOrDefault("output", ""),
                            (String) ex.getOrDefault("description", "")));
                } catch (Exception e) {
                    warnings.add("Failed to parse example: " + entry.getKey());
                }
            }
        }

        // Register the built-in prompts with a package-specific ID
        String promptId = "pkg:" + id;
        PromptTemplate promptTemplate = new PromptTemplate(promptId, name + " (built-in)",
                buildSystemPrompt(systemPrompt, List.of(), examples),
                userPrompt, outputSchema != null ? outputSchema : "");


        // Register the skill
        Skill skill = new Skill(id, name, description, promptId, provider, "auto", maxTokens, true,
                getEntryContent(entries, "SKILL.md"), references);
        promptManager.register(promptTemplate);
        skillRegistry.register(skill);

        log.info("Imported skill package: {} v{} ({} refs, {} examples)", id, version, references.size(), examples.size());
        return new ImportResult(true, "Skill '" + name + "' v" + version + " imported successfully",
                new SkillPackage(id, name, description, version, (String) manifest.getOrDefault("author", "unknown"),
                        systemPrompt, userPrompt, outputSchema, references, examples, provider, maxTokens, manifest),
                warnings);
    }

    private String buildSystemPrompt(String basePrompt, List<SkillPackage.SkillReference> references,
                                      List<SkillPackage.SkillExample> examples) {
        StringBuilder sb = new StringBuilder(basePrompt);
        if (!references.isEmpty()) {
            sb.append("\n\n## Reference Materials\n");
            for (SkillPackage.SkillReference ref : references) {
                sb.append("### ").append(ref.title()).append("\n").append(ref.content()).append("\n\n");
            }
        }
        if (!examples.isEmpty()) {
            sb.append("## Examples\n");
            for (SkillPackage.SkillExample ex : examples) {
                if (ex.description() != null) sb.append("### ").append(ex.description()).append("\n");
                sb.append("Input:\n").append(ex.input()).append("\n");
                sb.append("Output:\n").append(ex.output()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String getEntryContent(Map<String, byte[]> entries, String path) {
        byte[] data = entries.get(path);
        return data != null ? new String(data, StandardCharsets.UTF_8) : null;
    }

    private void addEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    public record ImportResult(boolean success, String message, SkillPackage skillPackage, List<String> warnings) {}
}
