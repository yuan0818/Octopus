package io.yuan.octopus.domain;

import java.util.List;

/**
 * Per-file change detail between two commits, carrying the changed line ranges
 * (in the NEW file's 1-based coordinates) so {@code ChangeMethodDetector} can
 * intersect them with {@code method_info.line_start..line_end}.
 *
 * <ul>
 *   <li>{@code filePath}     – absolute path of the changed {@code .java} file (NEW side)</li>
 *   <li>{@code oldFilePath}  – absolute path on the OLD side (set for RENAME/DELETE; null otherwise)</li>
 *   <li>{@code changeType}   – {@code ADD} / {@code MODIFY} / {@code DELETE} / {@code RENAME}</li>
 *   <li>{@code changedLineRanges} – list of {@code [start, end]} 1-based inclusive line ranges in the NEW file
 *       that were inserted or replaced; empty for {@code ADD} (whole file new) and {@code DELETE} (no new lines)</li>
 * </ul>
 */
public record FileLineChanges(String filePath,
                              String oldFilePath,
                              String changeType,
                              List<int[]> changedLineRanges) {
}
