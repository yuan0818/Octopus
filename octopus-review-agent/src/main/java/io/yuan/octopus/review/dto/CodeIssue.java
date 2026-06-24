package io.yuan.octopus.review.dto;

import java.util.List;

public class CodeIssue {

    private String severity;
    private String category;
    private String description;
    private List<String> codeReferences;
    private String suggestion;
    private String highlightedCode;

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getCodeReferences() { return codeReferences; }
    public void setCodeReferences(List<String> codeReferences) { this.codeReferences = codeReferences; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }

    public String getHighlightedCode() { return highlightedCode; }
    public void setHighlightedCode(String highlightedCode) { this.highlightedCode = highlightedCode; }
}
