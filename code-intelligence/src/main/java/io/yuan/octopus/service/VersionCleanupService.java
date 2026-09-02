package io.yuan.octopus.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 定期清理 MySQL 中超过保留期的旧版本元数据。
 * <p>
 * 保留策略（与 docs/sql/20260625_version-aware-review.sql 第 5 节的规范 SQL 一致）：
 * <ul>
 *   <li>is_head=true 的行（HEAD 版本）永不清理</li>
 *   <li>commit_id='legacy' 的旧数据永不清理（直到下次分析覆盖）</li>
 *   <li>有关联 tag 的版本（通过 review_session.commit_id 且 rs.tag 非空关联）保留</li>
 *   <li>无 tag 且超过保留天数的非 HEAD 版本清理</li>
 * </ul>
 * <p>
 * 默认保留 30 天。可通过 {@code analysis.version-retention-days} 配置。
 */
@Service
public class VersionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(VersionCleanupService.class);

    private final DataSource dataSource;
    private final int retentionDays;

    public VersionCleanupService(
            DataSource dataSource,
            @Value("${analysis.version-retention-days:30}") int retentionDays) {
        this.dataSource = dataSource;
        this.retentionDays = retentionDays;
    }

    /**
     * 每天凌晨 3:30 执行清理（错开 RepoCleanupService 的 3:00）。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanOldVersions() {
        log.info("Starting MySQL version cleanup (retention: {} days)", retentionDays);

        long deletedClasses = deleteOld("class_info");
        long deletedMethods = deleteOld("method_info");
        long deletedAnnotations = deleteOld("annotation_info");

        log.info("Version cleanup complete: {} classes, {} methods, {} annotations deleted",
                deletedClasses, deletedMethods, deletedAnnotations);
    }

    /**
     * 删除指定表中超过保留期的非 HEAD、非 legacy、非 review_session 关联的数据。
     * <p>
     * 优先执行带 review_session 保留过滤的完整语义（LEFT JOIN 排除仍被审查会话
     * 引用的版本）；review_session 表不在同一数据库时（SQLState 42S02）回退为
     * 无 JOIN 版本，避免清理任务整体失败。
     */
    private long deleteOld(String tableName) {
        String sqlWithSessionFilter = String.format("""
            DELETE t FROM %s t
            LEFT JOIN review_session rs ON t.commit_id = rs.commit_id AND rs.tag IS NOT NULL
            WHERE t.is_head = FALSE
              AND t.commit_id IS NOT NULL
              AND t.commit_id <> 'legacy'
              AND t.created_at < DATE_SUB(NOW(), INTERVAL ? DAY)
              AND rs.id IS NULL
            """, tableName);
        String sqlWithoutSessionFilter = String.format("""
            DELETE FROM %s
            WHERE is_head = FALSE
              AND commit_id IS NOT NULL
              AND commit_id <> 'legacy'
              AND created_at < DATE_SUB(NOW(), INTERVAL ? DAY)
            """, tableName);

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sqlWithSessionFilter)) {
                ps.setInt(1, retentionDays);
                return executeAndLog(ps, tableName, true);
            } catch (SQLException e) {
                if (isMissingObject(e)) {
                    log.warn("review_session not accessible ({}), falling back to cleanup without tag filter for {}",
                            e.getSQLState(), tableName);
                    try (PreparedStatement ps = conn.prepareStatement(sqlWithoutSessionFilter)) {
                        ps.setInt(1, retentionDays);
                        return executeAndLog(ps, tableName, false);
                    }
                }
                throw e;
            }
        } catch (SQLException e) {
            if (isMissingObject(e)) {
                // 版本化列/表还未迁移（ddl-auto 未建 created_at 等）：跳过而不是报错
                log.warn("Table {} not yet migrated, skipping cleanup: {}", tableName, e.getMessage());
            } else {
                log.error("Failed to clean {}: SQLState={}, {}", tableName, e.getSQLState(), e.getMessage());
            }
            return 0;
        }
    }

    private long executeAndLog(PreparedStatement ps, String tableName, boolean sessionFiltered) throws SQLException {
        int deleted = ps.executeUpdate();
        if (deleted > 0) {
            log.info("Cleaned {} rows from {} (session-filtered: {})", deleted, tableName, sessionFiltered);
        }
        return deleted;
    }

    /**
     * MySQL SQLState 42S02 = 表不存在，42S22 = 列不存在（"Unknown column"）。
     * 部分连接池会丢失 SQLState，兜底匹配消息关键词。
     */
    private boolean isMissingObject(SQLException e) {
        if (e.getSQLState() != null && e.getSQLState().startsWith("42")) {
            return true;
        }
        String message = e.getMessage();
        return message != null && (message.contains("Unknown column") || message.contains("doesn't exist"));
    }
}
