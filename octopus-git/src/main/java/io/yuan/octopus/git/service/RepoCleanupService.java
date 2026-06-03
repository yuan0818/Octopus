package io.yuan.octopus.git.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Periodically cleans up local Git repository directories based on
 * TTL, max count, and max disk size policies.
 * <p>
 * Uses directory last-modified time as the access heuristic (updated on
 * fetch/checkout). No database dependency.
 */
@Service
public class RepoCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RepoCleanupService.class);

    private record DirInfo(Path path, long sizeBytes, Instant lastModified) {}

    private final Path localRepoBase;
    private final int ttlDays;
    private final int maxCount;
    private final long maxSizeBytes;

    public RepoCleanupService(
            @Value("${octopus.git.local-repo-base:/data/repos}") String localRepoBase,
            @Value("${octopus.git.local-repo-ttl-days:30}") int ttlDays,
            @Value("${octopus.git.local-repo-max-count:50}") int maxCount,
            @Value("${octopus.git.local-repo-max-size-gb:100}") int maxSizeGb) {
        this.localRepoBase = Path.of(localRepoBase);
        this.ttlDays = ttlDays;
        this.maxCount = maxCount;
        this.maxSizeBytes = maxSizeGb * 1024L * 1024L * 1024L;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupScheduled() {
        log.info("Starting scheduled local repo cleanup");
        try {
            cleanup();
        } catch (Exception e) {
            log.error("Scheduled repo cleanup failed", e);
        }
    }

    public String cleanup() {
        if (!Files.isDirectory(localRepoBase)) {
            return "Local repo base directory does not exist: " + localRepoBase;
        }

        List<Path> dirs;
        try (Stream<Path> stream = Files.list(localRepoBase)) {
            dirs = stream.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.error("Failed to list local repo base directory", e);
            return "Failed: " + e.getMessage();
        }

        if (dirs.isEmpty()) return "No directories found";

        List<DirInfo> infos = dirs.stream()
                .map(dir -> new DirInfo(dir, dirSize(dir), lastModified(dir)))
                .sorted(Comparator.comparing(DirInfo::lastModified))
                .toList();

        long totalBefore = infos.stream().mapToLong(DirInfo::sizeBytes).sum();
        int deletedCount = 0;
        long deletedBytes = 0;
        StringBuilder summary = new StringBuilder();

        // Phase 1: TTL
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        for (DirInfo info : infos) {
            if (info.lastModified().isBefore(cutoff)) {
                deleteDir(info.path(), info.sizeBytes(), summary);
                deletedCount++;
                deletedBytes += info.sizeBytes();
            }
        }

        // Refresh list after TTL removals
        infos = infos.stream()
                .filter(d -> Files.isDirectory(d.path()))
                .sorted(Comparator.comparing(DirInfo::lastModified))
                .toList();

        // Phase 2: Max count
        while (infos.size() > maxCount) {
            DirInfo oldest = infos.getFirst();
            deleteDir(oldest.path(), oldest.sizeBytes(), summary);
            deletedCount++;
            deletedBytes += oldest.sizeBytes();
            infos = infos.stream().filter(d -> !d.path().equals(oldest.path())).toList();
        }

        // Phase 3: Max size
        long currentTotal = infos.stream().mapToLong(DirInfo::sizeBytes).sum();
        while (currentTotal > maxSizeBytes && !infos.isEmpty()) {
            DirInfo oldest = infos.getFirst();
            deleteDir(oldest.path(), oldest.sizeBytes(), summary);
            deletedCount++;
            deletedBytes += oldest.sizeBytes();
            currentTotal -= oldest.sizeBytes();
            infos = infos.stream().filter(d -> !d.path().equals(oldest.path())).toList();
        }

        long totalAfter = infos.stream().mapToLong(DirInfo::sizeBytes).sum();
        String result = String.format("Cleanup: removed %d dirs (%s), %s → %s. %s",
                deletedCount, formatBytes(deletedBytes),
                formatBytes(totalBefore), formatBytes(totalAfter), summary);
        log.info(result);
        return result;
    }

    private void deleteDir(Path path, long size, StringBuilder summary) {
        try {
            deleteRecursively(path);
            log.info("Deleted local repo: {} (size: {})", path.getFileName(), formatBytes(size));
            if (!summary.isEmpty()) summary.append("; ");
            summary.append(path.getFileName());
        } catch (IOException e) {
            log.error("Failed to delete: {}", path, e);
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
            }
        }
    }

    private long dirSize(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private Instant lastModified(Path dir) {
        try {
            return Files.readAttributes(dir, BasicFileAttributes.class).lastModifiedTime().toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
