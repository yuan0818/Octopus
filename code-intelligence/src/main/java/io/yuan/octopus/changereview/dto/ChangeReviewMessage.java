package io.yuan.octopus.changereview.dto;

/**
 * RabbitMQ message payload for the change-review queue. Carries only the task id;
 * the consumer loads full task state from {@code change_review_task} (single source
 * of truth) so the message stays small and survives serializer changes.
 */
public record ChangeReviewMessage(Long taskId) {
}
