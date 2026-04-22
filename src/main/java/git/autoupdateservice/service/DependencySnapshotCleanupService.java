package git.autoupdateservice.service;

import git.autoupdateservice.domain.LogType;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class DependencySnapshotCleanupService {

    private static final String OLD_SNAPSHOT_CONDITION = """
            select s.id
            from public.dependency_snapshot s
            where s.id <> ?
              and s.id <> coalesce((select active_snapshot_id from public.dependency_graph_state where id = 1), ?)
              and s.status <> 'BUILDING'
              and not exists (
                  select 1
                  from public.execution_run r
                  where r.dependency_snapshot_id = s.id
                    and r.status = 'RUNNING'
              )
              and s.started_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<UUID> pendingKeepSnapshotId = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dependency-snapshot-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    public void cleanupOldSnapshotsAsync(UUID keepSnapshotId) {
        if (keepSnapshotId == null) {
            return;
        }

        pendingKeepSnapshotId.set(keepSnapshotId);
        if (running.compareAndSet(false, true)) {
            executor.submit(this::runPendingCleanups);
        }
    }

    private void runPendingCleanups() {
        try {
            while (true) {
                UUID keepSnapshotId = pendingKeepSnapshotId.getAndSet(null);
                if (keepSnapshotId == null) {
                    return;
                }
                cleanupOldSnapshots(keepSnapshotId);
            }
        } finally {
            running.set(false);
            if (pendingKeepSnapshotId.get() != null && running.compareAndSet(false, true)) {
                executor.submit(this::runPendingCleanups);
            }
        }
    }

    private void cleanupOldSnapshots(UUID keepSnapshotId) {
        OffsetDateTime cutoff = OffsetDateTime.now();

        auditLogService.info(
                LogType.RUN_STARTED,
                "Dependency snapshot cleanup started",
                "{\"keepSnapshotId\":" + j(String.valueOf(keepSnapshotId)) + "}",
                null,
                "system",
                null
        );

        try {
            int scanLogs = deleteBySnapshot("public.dependency_scan_log", keepSnapshotId, cutoff);
            int impacts = deleteBySnapshot("public.common_module_impact", keepSnapshotId, cutoff);
            int edges = deleteBySnapshot("public.dependency_edge", keepSnapshotId, cutoff);
            int runsDetached = detachExecutionRuns(keepSnapshotId, cutoff);
            int snapshots = deleteSnapshots(keepSnapshotId, cutoff);

            auditLogService.info(
                    LogType.RUN_FINISHED,
                    "Dependency snapshot cleanup finished",
                    "{\"keepSnapshotId\":" + j(String.valueOf(keepSnapshotId))
                            + ",\"snapshots\":" + snapshots
                            + ",\"scanLogs\":" + scanLogs
                            + ",\"impacts\":" + impacts
                            + ",\"edges\":" + edges
                            + ",\"runsDetached\":" + runsDetached + "}",
                    null,
                    "system",
                    null
            );
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.RUN_FAILED,
                    "Dependency snapshot cleanup failed: " + safe(e.getMessage()),
                    "{\"keepSnapshotId\":" + j(String.valueOf(keepSnapshotId))
                            + ",\"error\":" + j(String.valueOf(e.getMessage())) + "}",
                    null,
                    "system",
                    null
            );
        }
    }

    private int deleteBySnapshot(String tableName, UUID keepSnapshotId, OffsetDateTime cutoff) {
        String sql = "delete from " + tableName + " where snapshot_id in (" + OLD_SNAPSHOT_CONDITION + ")";
        return jdbcTemplate.update(sql, keepSnapshotId, keepSnapshotId, cutoff);
    }

    private int detachExecutionRuns(UUID keepSnapshotId, OffsetDateTime cutoff) {
        String sql = """
                update public.execution_run
                set dependency_snapshot_id = null
                where dependency_snapshot_id in (
                """ + OLD_SNAPSHOT_CONDITION + ")";
        return jdbcTemplate.update(sql, keepSnapshotId, keepSnapshotId, cutoff);
    }

    private int deleteSnapshots(UUID keepSnapshotId, OffsetDateTime cutoff) {
        String sql = "delete from public.dependency_snapshot where id in (" + OLD_SNAPSHOT_CONDITION + ")";
        return jdbcTemplate.update(sql, keepSnapshotId, keepSnapshotId, cutoff);
    }

    private static String j(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ") + "\"";
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String s = value.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
