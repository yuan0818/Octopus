package io.yuan.octopus.review.dto;

import java.util.List;

public class ReviewResponse {

    private String sessionId;
    private String anchor;
    private ReviewMode reviewMode;
    private ReviewSummary summary;
    private List<RequirementCheck> requirementChecks;
    private List<CodeIssue> issues;
    private int involvedClasses;
    private int totalMethods;
    private boolean truncated;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getAnchor() { return anchor; }
    public void setAnchor(String anchor) { this.anchor = anchor; }

    public ReviewMode getReviewMode() { return reviewMode; }
    public void setReviewMode(ReviewMode reviewMode) { this.reviewMode = reviewMode; }

    public ReviewSummary getSummary() { return summary; }
    public void setSummary(ReviewSummary summary) { this.summary = summary; }

    public List<RequirementCheck> getRequirementChecks() { return requirementChecks; }
    public void setRequirementChecks(List<RequirementCheck> requirementChecks) { this.requirementChecks = requirementChecks; }

    public List<CodeIssue> getIssues() { return issues; }
    public void setIssues(List<CodeIssue> issues) { this.issues = issues; }

    public int getInvolvedClasses() { return involvedClasses; }
    public void setInvolvedClasses(int involvedClasses) { this.involvedClasses = involvedClasses; }

    public int getTotalMethods() { return totalMethods; }
    public void setTotalMethods(int totalMethods) { this.totalMethods = totalMethods; }

    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
}
