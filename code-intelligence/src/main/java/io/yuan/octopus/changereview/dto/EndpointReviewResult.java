package io.yuan.octopus.changereview.dto;

import java.util.List;

/**
 * Lightweight capture of a single endpoint's review result from review-agent.
 * Carries only the fields needed for change-level aggregation + Gerrit comment
 * (the full report stays in {@code review_session.full_report} on review-agent side).
 *
 * @param sessionId   review session id (used to populate {@code change_review_task.review_session_ids})
 * @param status      review summary status ({@code NORMAL} / {@code PARSING_FAILED})
 * @param topSeverity highest severity across findings
 * @param issues      list of findings (severity / category / description / suggestion)
 * @param errorMessage non-null when the review call failed
 */
public record EndpointReviewResult(String sessionId,
                                   String status,
                                   String topSeverity,
                                   List<ReviewIssue> issues,
                                   String errorMessage) {

    public boolean failed() {
        return errorMessage != null && !errorMessage.isBlank();
    }
}
