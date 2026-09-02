package io.yuan.octopus.review.dto;

public class ReviewSummary {

    private int totalRequirements;
    private int passed;
    private int failed;
    private int uncertain;
    private String topSeverity;
    /** 审查状态：NORMAL（默认）| PARSING_FAILED（LLM 输出格式连续校验失败，结果为空）。 */
    private String status = "NORMAL";

    public ReviewSummary() {}

    public ReviewSummary(int totalRequirements, int passed, int failed, int uncertain, String topSeverity) {
        this(totalRequirements, passed, failed, uncertain, topSeverity, "NORMAL");
    }

    public ReviewSummary(int totalRequirements, int passed, int failed, int uncertain,
                         String topSeverity, String status) {
        this.totalRequirements = totalRequirements;
        this.passed = passed;
        this.failed = failed;
        this.uncertain = uncertain;
        this.topSeverity = topSeverity;
        this.status = status != null ? status : "NORMAL";
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

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
