package io.yuan.octopus.review.dto;

import java.util.List;

public class RequirementCheck {

    private String requirementId;
    private String requirementTitle;
    private boolean passed;
    private String confidence;
    private String detail;
    private List<String> codeReferences;
    private String suggestion;
    private String highlightedCode;

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

    public String getRequirementTitle() { return requirementTitle; }
    public void setRequirementTitle(String requirementTitle) { this.requirementTitle = requirementTitle; }

    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }

    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public List<String> getCodeReferences() { return codeReferences; }
    public void setCodeReferences(List<String> codeReferences) { this.codeReferences = codeReferences; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }

    public String getHighlightedCode() { return highlightedCode; }
    public void setHighlightedCode(String highlightedCode) { this.highlightedCode = highlightedCode; }
}
