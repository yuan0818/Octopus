package io.yuan.octopus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VersionCleanupServiceTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement preparedStatement;

    private VersionCleanupService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new VersionCleanupService(dataSource, 30);
    }

    // ── 正常清理 ──────────────────────────────────────────────────

    @Test
    void shouldCleanOldVersionsSuccessfully() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenReturn(5, 10, 3);

        service.cleanOldVersions();

        // 3 个表（class_info, method_info, annotation_info），各执行 1 次
        verify(preparedStatement, times(3)).executeUpdate();
        verify(preparedStatement, times(3)).setInt(eq(1), eq(30));
    }

    // ── SQL 异常时优雅降级 ────────────────────────────────────────

    @Test
    void shouldGracefullyHandleSqlException() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

        // 不应抛异常
        assertDoesNotThrow(() -> service.cleanOldVersions());
    }

    // ── Unknown column 异常时的 warn 处理 ─────────────────────────

    @Test
    void shouldHandleUnknownColumnExceptionGracefully() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate())
                .thenThrow(new SQLException("Unknown column 'is_head' in 'where clause'"));

        // 不应抛异常
        assertDoesNotThrow(() -> service.cleanOldVersions());
    }

    // ── 0 行被删除时正常返回 ──────────────────────────────────────

    @Test
    void shouldHandleZeroRowsDeleted() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenReturn(0, 0, 0);

        assertDoesNotThrow(() -> service.cleanOldVersions());
        verify(preparedStatement, times(3)).executeUpdate();
    }

    // ── 自定义保留天数 ────────────────────────────────────────────

    @Test
    void shouldUseCustomRetentionDays() throws Exception {
        VersionCleanupService customService = new VersionCleanupService(dataSource, 60);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenReturn(0);

        customService.cleanOldVersions();

        verify(preparedStatement, times(3)).setInt(eq(1), eq(60));
    }
}
