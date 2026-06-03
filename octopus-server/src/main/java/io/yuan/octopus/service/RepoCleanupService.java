package io.yuan.octopus.service;

import io.yuan.octopus.entity.JavaProjectEntity;
import io.yuan.octopus.repository.JavaProjectRepository;
import io.yuan.octopus.repository.RepoConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Periodically cleans up local Git repository directories to control disk usage.
 * <p>
 * Three retention policies are applied in order:
 * <ol>
 *   <li><b>TTL</b> — Directories not analysed within {@code ttlDays} are removed</li>
 *   <li><b>Max count</b> — If more than {@code maxCount} directories exist,
 *       the least-recently-used ones are removed</li>
 *   <li><b>Max size</b> — If total size exceeds {@code maxSizeGb}, directories
 *       are removed (LRU first) until the limit is met</li>
 * </ol>
 * <p>
 * Only directories that match an active {@code RepoConfig} are considered for
 * cleanup; orphan directories (no matching config) are always removed.
 */
@Service
public class RepoCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RepoCleanupService.class);

    private record DirInfo(Path path, long sizeBytes, Instant lastAnalyzed, boolean active) {}

    private final Path localRepoBase;
    private final int ttlDays;
    private final int maxCount;
    private final long maxSizeBytes;
    private final RepoConfigRepository repoConfigRepository;
    private final JavaProjectRepository javaProjectRepository;

    public RepoCleanupService(
            @Value("${spring.git.local-repo-base:/data/repos}") String localRepoBase,
            @Value("${octopus.git.local-repo-ttl-days:30}") int ttlDays,
            @Value("${octopus.git.local-repo-max-count:50}") int maxCount,
            @Value("${octopus.git.local-repo-max-size-gb:100}") int maxSizeGb,
            RepoConfigRepository repoConfigRepository,
            JavaProjectRepository javaProjectRepository) {
        this.localRepoBase = Path.of(localRepoBase);
        this.ttlDays = ttlDays;
        this.maxCount = maxCount;
        this.maxSizeBytes = maxSizeGb * 1024L * 1024L * 1024L;
        this.repoConfigRepository = repoConfigRepository;
        this.javaProjectRepository = javaProjectRepository;
    }

    /**
     * Runs daily at 3:00 AM.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupScheduled() {
        log.info("Starting scheduled local repo cleanup");
        try {
            cleanup();
        } catch (Exception e) {
            log.error("Scheduled repo cleanup failed", e);
        }
    }

    /**
     * Execute cleanup immediately (also callable via JMX / admin endpoint).
     *
     * @return summary of cleanup actions
     */
    public String cleanup() {
        if (!Files.isDirectory(localRepoBase)) {
            return "Local repo base directory does not exist: " + localRepoBase;
        }

        // Collect all directories in the base path
        List<Path> dirs;
        try (Stream<Path> stream = Files.list(localRepoBase)) {
            dirs = stream.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.error("Failed to list local repo base directory: {}", localRepoBase, e);
            return "Failed to list directory: " + e.getMessage();
        }

        if (dirs.isEmpty()) {
            log.info("No local repo directories found — nothing to clean up");
            return "No directories found";
        }

        // Build a set of active repo directory names from RepoConfig
        Set<String> activeNames = repoConfigRepository.findAll().stream()
                .map(rc -> GitService.extractRepoName(rc.getRepoUrl()))
                .collect(Collectors.toSet());

        // Map each directory to its last-analysis time and size
        Map<String, Instant> lastAnalyzedMap = buildLastAnalyzedMap();

        List<DirInfo> infos = dirs.stream()
                .map(dir -> {
                    String name = dir.getFileName().toString();
                    long size = dirSize(dir);
                    Instant lastAnalyzed = lastAnalyzedMap.getOrDefault(name, Instant.EPOCH);
                    boolean active = activeNames.contains(name);
                    return new DirInfo(dir, size, lastAnalyzed, active);
                })
                .sorted(Comparator.comparing(DirInfo::lastAnalyzed))
                .toList();

        long totalSizeBefore = infos.stream().mapToLong(DirInfo::sizeBytes).sum();
        int deletedCount = 0;
        long deletedBytes = 0;
        StringBuilder summary = new StringBuilder();

        // Phase 1: Remove orphans (directories with no matching RepoConfig)
        for (DirInfo info : infos) {
            if (!info.active()) {
                deleteDir(info, summary);
                deletedCount++;
                deletedBytes += info.sizeBytes();
            }
        }

        // Refresh list after removing orphans
        infos = infos.stream().filter(DirInfo::active).toList();

        // Phase 2: TTL policy
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        for (DirInfo info : infos) {
            if (info.lastAnalyzed().isBefore(cutoff)) {
                deleteDir(info, summary);
                deletedCount++;
                deletedBytes += info.sizeBytes();
            }
        }

        // Refresh list after TTL removals (keep remaining, sorted LRU first)
        infos = infos.stream()
                .filter(d -> Files.isDirectory(d.path()))
                .sorted(Comparator.comparing(DirInfo::lastAnalyzed))
                .toList();

        // Phase 3: Max count policy
        while (infos.size() > maxCount) {
            DirInfo oldest = infos.getFirst();
            deleteDir(oldest, summary);
            deletedCount++;
            deletedBytes += oldest.sizeBytes();
            infos = infos.stream().filter(d -> !d.path().equals(oldest.path())).toList();
        }

        // Phase 4: Max size policy
        long currentTotal = infos.stream().mapToLong(DirInfo::sizeBytes).sum();
        while (currentTotal > maxSizeBytes && !infos.isEmpty()) {
            DirInfo oldest = infos.getFirst();
            deleteDir(oldest, summary);
            deletedCount++;
            deletedBytes += oldest.sizeBytes();
            currentTotal -= oldest.sizeBytes();
            infos = infos.stream().filter(d -> !d.path().equals(oldest.path())).toList();
        }

        // Summary
        long totalSizeAfter = infos.stream().mapToLong(DirInfo::sizeBytes).sum();
        String result = String.format(
                "Cleanup complete: removed %d directories (%s), total size %s → %s. %s",
                deletedCount,
                formatBytes(deletedBytes),
                formatBytes(totalSizeBefore),
                formatBytes(totalSizeAfter),
                summary);
        log.info(result);
        return result;
    }

    /**
     * Builds a map from repo directory name → latest analysis timestamp.
     */
    private Map<String, Instant> buildLastAnalyzedMap() {
        Map<Long, String> configIdToRepoName = repoConfigRepository.findAll().stream()
                .collect(Collectors.toMap(
                        rc -> rc.getId(),
                        rc -> GitService.extractRepoName(rc.getRepoUrl()),
                        (a, b) -> a));

        Map<String, Instant> result = new java.util.HashMap<>();
        for (JavaProjectEntity jp : javaProjectRepository.findAll()) {
            if (jp.getAnalyzedAt() == null) continue;
            String repoName = configIdToRepoName.getOrDefault(
                    jp.getRepoConfigId(), String.valueOf(jp.getRepoConfigId()));
            Instant analyzedInstant = java.sql.Timestamp.valueOf(jp.getAnalyzedAt()).toInstant();
            result.merge(repoName, analyzedInstant,
                    (existing, newer) -> existing.isAfter(newer) ? existing : newer);
        }
        return result;
    }

    private long dirSize(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            log.warn("Failed to compute size for directory: {}", dir, e);
            return 0;
        }
    }

    private void deleteDir(DirInfo info, StringBuilder summary) {
        try {
            deleteRecursively(info.path());
            log.info("Deleted local repo: {} (size: {}, last analysed: {})",
                    info.path().getFileName(), formatBytes(info.sizeBytes()), info.lastAnalyzed());
            if (!summary.isEmpty()) {
                summary.append("; ");
            }
            summary.append(info.path().getFileName());
        } catch (IOException e) {
            log.error("Failed to delete directory: {}", info.path(), e);
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                log.warn("Failed to delete: {}", p, e);
                            }
                        });
            }
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
