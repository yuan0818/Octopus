package io.yuan.octopus.review.dto;

public enum ReviewMode {
    REQUIREMENT_AWARE,
    RISK_BASED;

    public static ReviewMode infer(ReviewRequest request) {
        if (request.getReviewMode() != null) {
            return request.getReviewMode();
        }
        boolean hasRequirements = request.getRequirements() != null && !request.getRequirements().isEmpty();
        boolean hasDoc = request.getDocPath() != null && !request.getDocPath().isBlank();
        return hasRequirements || hasDoc ? REQUIREMENT_AWARE : RISK_BASED;
    }
}
