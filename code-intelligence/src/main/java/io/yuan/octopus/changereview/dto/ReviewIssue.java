package io.yuan.octopus.changereview.dto;

/**
 * A single finding from a review-agent review (mirrors the relevant subset of
 * {@code ReviewResponse.issues[]}).
 */
public record ReviewIssue(String severity, String category, String description, String suggestion) {
}
