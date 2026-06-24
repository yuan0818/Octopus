package io.yuan.octopus.review.parsing;

import io.yuan.octopus.review.dto.RequirementItem;
import io.yuan.octopus.review.dto.ReviewRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses requirements from either API fields or Markdown documents.
 * <p>
 * Supports four formats in order of precedence:
 * <ol>
 *   <li><b>API fields</b> — {@code request.requirements} as a list of structured items</li>
 *   <li><b>Strict Markdown</b> — {@code ## REQ-001: Title\nDescription}</li>
 *   <li><b>Relaxed Markdown</b> — numbered / bullet lists, h2/h3 headings</li>
 *   <li><b>Raw text</b> — entire doc content sent as a single "文档" requirement</li>
 * </ol>
 */
@Component
public class RequirementParser {

    private static final Logger log = LoggerFactory.getLogger(RequirementParser.class);

    // ## REQ-001: 标题  /  ### REQ-001: 标题
    private static final Pattern STRICT_PATTERN =
            Pattern.compile("##?\\s*(REQ-\\d+)\\s*[:：]\\s*(.+?)\\n((?:(?!##?\\s*REQ-\\d+\\s*[:：]).)*)", Pattern.DOTALL);

    // 宽松模式：## 标题 / ### 标题 / #### 标题（作为 section 标题）
    private static final Pattern SECTION_PATTERN =
            Pattern.compile("(?:^|\\n)\\s*(#{2,4})\\s+(.+?)\\n((?:(?!#{2,4}\\s).)*)", Pattern.DOTALL);

    // 1. 某个需求  /  - 某个需求  /  * 某个需求
    private static final Pattern LIST_ITEM_PATTERN =
            Pattern.compile("(?:^|\\n)\\s*(?:\\d+\\.\\s*|[-*]\\s+)(.+?)(?=\\n\\s*(?:\\d+\\.\\s*|[-*]\\s+|$)|\\n{2,}|$)", Pattern.DOTALL);

    private static final int MAX_REQUIREMENTS = 50;

    /**
     * Parse requirements from the request. Returns the list from
     * {@code request.requirements} (Way A) or parses the document
     * at {@code request.docPath} (Way B/C/D).
     */
    public List<RequirementItem> parse(ReviewRequest request) {
        // Way A: API fields — structured list from caller
        if (request.getRequirements() != null && !request.getRequirements().isEmpty()) {
            List<RequirementItem> items = request.getRequirements();
            if (items.size() > MAX_REQUIREMENTS) {
                log.warn("Requirements truncated from {} to {}", items.size(), MAX_REQUIREMENTS);
                return items.subList(0, MAX_REQUIREMENTS);
            }
            return items;
        }

        // Way B/C/D: document file
        if (request.getDocPath() != null && !request.getDocPath().isBlank()) {
            return parseMarkdownFile(request.getDocPath());
        }

        return List.of();
    }

    /**
     * Parse a Markdown/plain-text file at the given path.
     */
    public List<RequirementItem> parseMarkdownFile(String docPath) {
        try {
            String content = Files.readString(Path.of(docPath));
            return parseDocument(content, docPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read requirements document: " + docPath, e);
        }
    }

    /**
     * Parse document content into requirement items using layered strategy:
     * <ol>
     *   <li>Try strict format: {@code ## REQ-XXX: Title}</li>
     *   <li>Fallback to relaxed: sections / numbered lists / bullets</li>
     *   <li>Last resort: send entire content as a single context block</li>
     * </ol>
     */
    public List<RequirementItem> parseDocument(String content, String source) {
        // 1. Try strict format
        List<RequirementItem> items = parseStrict(content);
        if (!items.isEmpty()) {
            log.info("Parsed {} requirements from '{}' (strict format)", items.size(), source);
            return cap(items);
        }

        // 2. Try relaxed format — sections or lists
        items = parseRelaxed(content);
        if (!items.isEmpty()) {
            log.info("Parsed {} requirements from '{}' (relaxed format)", items.size(), source);
            return cap(items);
        }

        // 3. Raw text fallback — whole document as one requirement
        String body = content.strip();
        log.info("No structured requirements found in '{}'; sending full doc as context ({} chars)", source, body.length());
        items = List.of(new RequirementItem("DOC-001", "需求文档", body));
        return cap(items);
    }

    // ── Strict format: ## REQ-001: Title ──────────────────────────────

    List<RequirementItem> parseStrict(String content) {
        Matcher matcher = STRICT_PATTERN.matcher(content);
        List<RequirementItem> items = new ArrayList<>();
        while (matcher.find()) {
            items.add(new RequirementItem(
                    matcher.group(1).trim(),
                    matcher.group(2).trim(),
                    matcher.group(3).strip()));
        }
        return items;
    }

    // ── Relaxed format: sections / numbered lists / bullets ──────────

    List<RequirementItem> parseRelaxed(String content) {
        // Try section headings first (## / ### / ####)
        List<RequirementItem> items = parseSections(content);
        if (!items.isEmpty()) return items;

        // Then try numbered / bullet lists
        return parseListItems(content);
    }

    private List<RequirementItem> parseSections(String content) {
        Matcher matcher = SECTION_PATTERN.matcher(content);
        List<RequirementItem> items = new ArrayList<>();
        int idx = 0;
        while (matcher.find()) {
            idx++;
            String id = String.format("REQ-%03d", idx);
            String title = matcher.group(2).trim();
            // Clean markdown link syntax from title
            title = title.replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1").strip();
            String desc = matcher.group(3).strip();
            items.add(new RequirementItem(id, title, desc));
        }
        return items;
    }

    private List<RequirementItem> parseListItems(String content) {
        Matcher matcher = LIST_ITEM_PATTERN.matcher(content);
        List<RequirementItem> items = new ArrayList<>();
        int idx = 0;
        while (matcher.find()) {
            idx++;
            String text = matcher.group(1).strip();
            if (text.isBlank()) continue;
            // Use the first sentence or up to 200 chars as title
            String title = text.length() <= 200 ? text : text.substring(0, 200) + "...";
            String id = String.format("REQ-%03d", idx);
            items.add(new RequirementItem(id, title, text));
        }
        return items;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private List<RequirementItem> cap(List<RequirementItem> items) {
        if (items.size() <= MAX_REQUIREMENTS) return items;
        log.warn("Requirements truncated from {} to {}", items.size(), MAX_REQUIREMENTS);
        return items.subList(0, MAX_REQUIREMENTS);
    }
}
