package io.yuan.octopus.git.dto;

/**
 * Request body for the diff endpoint.
 */
public record DiffRequest(
    String fromTag,
    String toTag
) {}
