package io.yuan.octopus.review.service;

import com.google.gson.*;
import io.yuan.octopus.review.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses the LLM's JSON response into a structured ReviewResponse.
 * Supports two formats:
 * - Legacy: { "requirementChecks": [...], "issues": [...] }
 * - New (Endpoint Requirement Review): [ { "id", "title", "category", ... }, ... ]
 */
@Component
public class ReviewResultParser {

    private static final Logger log = LoggerFactory.getLogger(ReviewResultParser.class);
    private static final Gson gson = new Gson();
    private static final List<String> SEVERITY_ORDER = List.of("BLOCKER", "CRITICAL", "WARNING", "INFO");

    public ReviewResponse parse(String llmContent, Map<String, Object> chainMetadata) {
        return parse(llmContent, chainMetadata, ReviewMode.REQUIREMENT_AWARE);
    }

    public ReviewResponse parse(String llmContent, Map<String, Object> chainMetadata, ReviewMode reviewMode) {
        ReviewResponse response = new ReviewResponse();
        response.setAnchor((String) chainMetadata.getOrDefault("anchor", "unknown"));
        response.setReviewMode(reviewMode);

        try {
            String cleaned = cleanContent(llmContent);

            if (cleaned.startsWith("[")) {
                parseNewFormat(cleaned, response, reviewMode);
            } else {
                parseLegacyFormat(cleaned, response, reviewMode);
            }
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            response.setSummary(new ReviewSummary(0, 0, 0, 0, "WARNING"));
            response.setRequirementChecks(List.of());
            response.setIssues(List.of());
        }

        return response;
    }

    // ── New format: JSON array of issues ────────────────────────────

    private void parseNewFormat(String content, ReviewResponse response, ReviewMode reviewMode) {
        JsonArray array = extractJsonArray(content);

        List<CodeIssue> issues = new ArrayList<>();
        List<RequirementCheck> checks = new ArrayList<>();

        for (JsonElement elem : array) {
            JsonObject obj = elem.getAsJsonObject();
            String category = getString(obj, "category");
            String severity = mapSeverity(getString(obj, "severity"));
            String title = getString(obj, "title");
            String summary = getString(obj, "summary");
            String suggestion = getString(obj, "suggestion");
            String confidence = getString(obj, "confidence");
            String id = getString(obj, "id");

            // Build description from title + summary
            String description = title != null ? title : "";
            if (summary != null && !summary.isBlank()) {
                description += (description.isEmpty() ? "" : "\n") + summary;
            }

            // Extract code references from codeEvidence array
            List<String> codeRefs = new ArrayList<>();
            String highlightedCode = null;
            JsonArray codeEvidence = obj.getAsJsonArray("codeEvidence");
            if (codeEvidence != null) {
                for (JsonElement ce : codeEvidence) {
                    JsonObject ceObj = ce.getAsJsonObject();
                    String symbol = getString(ceObj, "symbol");
                    if (symbol != null) codeRefs.add(symbol);
                    if (highlightedCode == null) {
                        highlightedCode = getString(ceObj, "snippet");
                    }
                }
            }

            // Build CodeIssue
            CodeIssue issue = new CodeIssue();
            issue.setSeverity(severity);
            issue.setCategory(category != null ? category : "UNKNOWN");
            issue.setDescription(description);
            issue.setCodeReferences(codeRefs);
            issue.setSuggestion(suggestion);
            issue.setHighlightedCode(highlightedCode);
            issues.add(issue);

            // If REQUIREMENT_MISMATCH, also generate a RequirementCheck (passed=false)
            if (reviewMode == ReviewMode.REQUIREMENT_AWARE && "REQUIREMENT_MISMATCH".equals(category)) {
                RequirementCheck check = new RequirementCheck();
                check.setRequirementId(id != null ? id : "REQ-?");
                check.setRequirementTitle(title != null ? title : "");
                check.setPassed(false);
                check.setConfidence(confidence != null ? confidence : "MEDIUM");
                check.setDetail(summary != null ? summary : "");
                check.setCodeReferences(codeRefs);
                check.setSuggestion(suggestion);
                check.setHighlightedCode(highlightedCode);
                checks.add(check);
            }
        }

        // Sort by severity
        issues.sort(Comparator.comparingInt(
                i -> SEVERITY_ORDER.indexOf(i.getSeverity() != null ? i.getSeverity() : "INFO")));

        response.setIssues(issues);
        response.setRequirementChecks(checks);

        // Build summary
        int totalReq = checks.size();
        int passed = 0;
        int failed = checks.size();
        int uncertain = (int) checks.stream().filter(c -> "LOW".equals(c.getConfidence())).count();

        String topSeverity = issues.stream()
                .map(CodeIssue::getSeverity)
                .filter(s -> SEVERITY_ORDER.contains(s))
                .min(Comparator.comparingInt(SEVERITY_ORDER::indexOf))
                .orElse("INFO");

        response.setSummary(new ReviewSummary(totalReq, passed, failed, uncertain, topSeverity));
    }

    // ── Legacy format: { requirementChecks, issues } ────────────────

    private void parseLegacyFormat(String content, ReviewResponse response, ReviewMode reviewMode) {
        JsonObject root = extractJsonObject(content);

        List<RequirementCheck> checks = new ArrayList<>();
        JsonArray checksArray = root.getAsJsonArray("requirementChecks");
        if (checksArray != null) {
            for (JsonElement elem : checksArray) {
                JsonObject obj = elem.getAsJsonObject();
                RequirementCheck check = new RequirementCheck();
                check.setRequirementId(getString(obj, "requirementId"));
                check.setRequirementTitle(getString(obj, "requirementTitle"));
                check.setPassed(getBoolean(obj, "passed"));
                check.setConfidence(getString(obj, "confidence"));
                check.setDetail(getString(obj, "detail"));
                check.setCodeReferences(getStringList(obj, "codeReferences"));
                check.setSuggestion(getString(obj, "suggestion"));
                check.setHighlightedCode(getString(obj, "highlightedCode"));
                checks.add(check);
            }
        }
        if (reviewMode == ReviewMode.RISK_BASED) {
            checks = List.of();
        }
        response.setRequirementChecks(checks);

        List<CodeIssue> issues = new ArrayList<>();
        JsonArray issuesArray = root.getAsJsonArray("issues");
        if (issuesArray != null) {
            for (JsonElement elem : issuesArray) {
                JsonObject obj = elem.getAsJsonObject();
                CodeIssue issue = new CodeIssue();
                issue.setSeverity(getString(obj, "severity"));
                issue.setCategory(getString(obj, "category"));
                issue.setDescription(getString(obj, "description"));
                issue.setCodeReferences(getStringList(obj, "codeReferences"));
                issue.setSuggestion(getString(obj, "suggestion"));
                issue.setHighlightedCode(getString(obj, "highlightedCode"));
                issues.add(issue);
            }
        }
        issues.sort(Comparator.comparingInt(
                i -> SEVERITY_ORDER.indexOf(i.getSeverity() != null ? i.getSeverity() : "INFO")));
        response.setIssues(issues);

        long passed = checks.stream().filter(RequirementCheck::isPassed).count();
        long failed = checks.stream().filter(c -> !c.isPassed()).count();
        long uncertain = checks.stream().filter(c -> "LOW".equals(c.getConfidence())).count();

        String topSeverity = issues.stream()
                .map(CodeIssue::getSeverity)
                .filter(s -> SEVERITY_ORDER.contains(s))
                .min(Comparator.comparingInt(SEVERITY_ORDER::indexOf))
                .orElse("INFO");

        response.setSummary(new ReviewSummary(
                checks.size(), (int) passed, (int) failed, (int) uncertain, topSeverity));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private String mapSeverity(String severity) {
        if (severity == null) return "INFO";
        return switch (severity.toUpperCase()) {
            case "HIGH" -> "BLOCKER";
            case "MEDIUM" -> "WARNING";
            case "LOW" -> "INFO";
            default -> severity.toUpperCase();
        };
    }

    private String cleanContent(String content) {
        content = content.trim();
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            if (firstNewline > 0) content = content.substring(firstNewline + 1);
            if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
            content = content.trim();
        }
        return content;
    }

    private JsonArray extractJsonArray(String content) {
        if (content.startsWith("[")) {
            try {
                return gson.fromJson(content, JsonArray.class);
            } catch (JsonSyntaxException ignored) {}
        }
        // Bracket-matching fallback
        int start = content.indexOf('[');
        if (start >= 0) {
            int depth = 0;
            for (int i = start; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        return gson.fromJson(content.substring(start, i + 1), JsonArray.class);
                    }
                }
            }
        }
        throw new JsonSyntaxException("No JSON array found in LLM response");
    }

    private JsonObject extractJsonObject(String content) {
        if (content.startsWith("{")) {
            try {
                return gson.fromJson(content, JsonObject.class);
            } catch (JsonSyntaxException ignored) {}
        }
        int start = content.indexOf('{');
        if (start >= 0) {
            int depth = 0;
            for (int i = start; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return gson.fromJson(content.substring(start, i + 1), JsonObject.class);
                    }
                }
            }
        }
        throw new JsonSyntaxException("No JSON object found in LLM response");
    }

    private String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : null;
    }

    private boolean getBoolean(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() && el.getAsBoolean();
    }

    private List<String> getStringList(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonArray()) {
            List<String> result = new ArrayList<>();
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonNull()) result.add(e.getAsString());
            }
            return result;
        }
        return List.of();
    }
}
