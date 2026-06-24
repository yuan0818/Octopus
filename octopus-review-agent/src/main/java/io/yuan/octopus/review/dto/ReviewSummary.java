package io.yuan.octopus.review.dto;

public class ReviewSummary {

    private int totalRequirements;
    private int passed;
    private int failed;
    private int uncertain;
    private String topSeverity;

    public ReviewSummary() {}

    public ReviewSummary(int totalRequirements, int passed, int failed, int uncertain, String topSeverity) {
        this.totalRequirements = totalRequirements;
        this.passed = passed;
        this.failed = failed;
        this.uncertain = uncertain;
        this.topSeverity = topSeverity;
    }

    public int getTotalRequirements() { return totalRequirements; }
    public void setTotalRequirements(int totalRequirements) { this.totalRequirements = totalRequirements; }

    public int getPassed() { return passed; }
    public void setPassed(int passed) { this.passed = passed; }

    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }

    public int getUncertain() { return uncertain; }
    public void setUncertain(int uncertain) { this.uncertain = uncertain; }

    public String getTopSeverity() { return topSeverity; }
    public void setTopSeverity(String topSeverity) { this.topSeverity = topSeverity; }
}
