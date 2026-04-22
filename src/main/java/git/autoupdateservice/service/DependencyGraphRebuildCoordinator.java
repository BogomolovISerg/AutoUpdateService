package git.autoupdateservice.service;

import git.autoupdateservice.domain.DependencyGraphState;
import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.DependencySnapshotStatus;
import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.repo.DependencySnapshotRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
@RequiredArgsConstructor
public class DependencyGraphRebuildCoordinator {

    private final DependencyGraphStateService dependencyGraphStateService;
    private final DependencyTreeBuildService dependencyTreeBuildService;
    private final DependencySnapshotRepository dependencySnapshotRepository;
    private final AuditLogService auditLogService;

    private final Object monitor = new Object();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dependency-graph-rebuild");
        thread.setDaemon(true);
        return thread;
    });

    private Future<?> currentTask;
    private boolean synchronousRunning;

    public StartResult startIfStaleAsync(String reason, UUID runId) {
        DependencyGraphState state = dependencyGraphStateService.getState();
        if (state == null || !state.isGraphIsStale()) {
            return new StartResult(false, false, activeSnapshot(state), false);
        }

        synchronized (monitor) {
            if (isRunningLocked()) {
                return new StartResult(false, true, activeSnapshot(state), true);
            }
            markOrphanBuildingSnapshotsFailedLocked("before async rebuild");

            currentTask = executor.submit(() -> runRebuild(reason, runId));
            monitor.notifyAll();
            return new StartResult(true, true, activeSnapshot(state), true);
        }
    }

    public WaitResult startIfStaleAndWait(String reason, UUID runId, Duration timeout) {
        StartResult startResult = startIfStaleAsync(reason, runId);
        if (!startResult.stale()) {
            return WaitResult.ready(startResult.activeSnapshot(), startResult.started(), startResult.alreadyRunning());
        }
        return waitForCompletion(timeout, startResult.started(), startResult.alreadyRunning());
    }

    public WaitResult waitForCompletion(Duration timeout, boolean started, boolean alreadyRunning) {
        long timeoutMillis = timeout == null ? 0L : Math.max(0L, timeout.toMillis());
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (true) {
            DependencyGraphState state = dependencyGraphStateService.getState();
            DependencySnapshot activeSnapshot = activeSnapshot(state);
            boolean stale = state != null && state.isGraphIsStale();
            if (!stale) {
                return WaitResult.ready(activeSnapshot, started, alreadyRunning);
            }

            boolean running = isRunning();
            if (!running) {
                markOrphanBuildingSnapshotsFailed("while waiting rebuild");
                return WaitResult.failed(activeSnapshot, started, alreadyRunning);
            }

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                return WaitResult.timeout(activeSnapshot, started, alreadyRunning);
            }

            synchronized (monitor) {
                try {
                    monitor.wait(Math.min(remaining, 1000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return WaitResult.interrupted(activeSnapshot, started, alreadyRunning);
                }
            }
        }
    }

    public DependencySnapshot rebuildNowIfIdle(UUID runId) {
        synchronized (monitor) {
            if (isRunningLocked()) {
                throw new IllegalStateException("Пересчет графа зависимостей уже выполняется");
            }
            markOrphanBuildingSnapshotsFailedLocked("before manual rebuild");
            synchronousRunning = true;
        }

        DependencySnapshot snapshot = null;
        try {
            snapshot = dependencyTreeBuildService.fullRebuild();
            return snapshot;
        } finally {
            synchronized (monitor) {
                synchronousRunning = false;
                monitor.notifyAll();
            }
            auditRebuildFinished(snapshot, runId);
        }
    }

    private void runRebuild(String reason, UUID runId) {
        DependencySnapshot snapshot = null;
        try {
            auditLogService.info(
                    LogType.RUN_STARTED,
                    "Dependency graph rebuild started",
                    "{\"reason\":" + j(reason) + "}",
                    null,
                    "system",
                    runId
            );
            snapshot = dependencyTreeBuildService.fullRebuild();
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.RUN_FAILED,
                    "Dependency graph rebuild failed: " + safe(e.getMessage()),
                    "{\"reason\":" + j(reason) + ",\"error\":" + j(String.valueOf(e.getMessage())) + "}",
                    null,
                    "system",
                    runId
            );
        } finally {
            synchronized (monitor) {
                currentTask = null;
                monitor.notifyAll();
            }
            auditRebuildFinished(snapshot, runId);
        }
    }

    private void auditRebuildFinished(DependencySnapshot snapshot, UUID runId) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.getStatus() == DependencySnapshotStatus.READY) {
            auditLogService.info(
                    LogType.RUN_FINISHED,
                    "Dependency graph rebuild finished",
                    "{\"snapshotId\":" + j(String.valueOf(snapshot.getId())) + ",\"status\":\"READY\"}",
                    null,
                    "system",
                    runId
            );
        } else {
            auditLogService.warn(
                    LogType.RUN_FAILED,
                    "Dependency graph rebuild finished with status " + snapshot.getStatus(),
                    "{\"snapshotId\":" + j(String.valueOf(snapshot.getId())) + ",\"status\":" + j(String.valueOf(snapshot.getStatus())) + "}",
                    null,
                    "system",
                    runId
            );
        }
    }

    private boolean isRunning() {
        synchronized (monitor) {
            return isRunningLocked();
        }
    }

    private boolean isRunningLocked() {
        return synchronousRunning || (currentTask != null && !currentTask.isDone());
    }

    private void markOrphanBuildingSnapshotsFailed(String reason) {
        synchronized (monitor) {
            if (!isRunningLocked()) {
                markOrphanBuildingSnapshotsFailedLocked(reason);
            }
        }
    }

    private void markOrphanBuildingSnapshotsFailedLocked(String reason) {
        List<DependencySnapshot> buildingSnapshots =
                dependencySnapshotRepository.findByStatusOrderByStartedAtAsc(DependencySnapshotStatus.BUILDING);
        if (buildingSnapshots.isEmpty()) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        for (DependencySnapshot snapshot : buildingSnapshots) {
            snapshot.setStatus(DependencySnapshotStatus.FAILED);
            snapshot.setFinishedAt(now);
            snapshot.setNotes(appendNote(snapshot.getNotes(),
                    "Сканирование помечено как завершенное с ошибкой: найден залипший статус BUILDING (" + reason + ")"));
        }
        dependencySnapshotRepository.saveAll(buildingSnapshots);

        auditLogService.warn(
                LogType.RUN_FAILED,
                "Orphan dependency graph BUILDING snapshots marked FAILED",
                "{\"count\":" + buildingSnapshots.size() + ",\"reason\":" + j(reason) + "}",
                null,
                "system",
                null
        );
    }

    private static String appendNote(String current, String note) {
        if (current == null || current.isBlank()) {
            return note;
        }
        return current + "\n" + note;
    }

    private static DependencySnapshot activeSnapshot(DependencyGraphState state) {
        return state == null ? null : state.getActiveSnapshot();
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

    public record StartResult(
            boolean started,
            boolean alreadyRunning,
            DependencySnapshot activeSnapshot,
            boolean stale
    ) {
    }

    public record WaitResult(
            Status status,
            DependencySnapshot activeSnapshot,
            boolean started,
            boolean alreadyRunning
    ) {
        public static WaitResult ready(DependencySnapshot activeSnapshot, boolean started, boolean alreadyRunning) {
            return new WaitResult(Status.READY, activeSnapshot, started, alreadyRunning);
        }

        public static WaitResult timeout(DependencySnapshot activeSnapshot, boolean started, boolean alreadyRunning) {
            return new WaitResult(Status.TIMEOUT, activeSnapshot, started, alreadyRunning);
        }

        public static WaitResult failed(DependencySnapshot activeSnapshot, boolean started, boolean alreadyRunning) {
            return new WaitResult(Status.FAILED, activeSnapshot, started, alreadyRunning);
        }

        public static WaitResult interrupted(DependencySnapshot activeSnapshot, boolean started, boolean alreadyRunning) {
            return new WaitResult(Status.INTERRUPTED, activeSnapshot, started, alreadyRunning);
        }

        public boolean ready() {
            return status == Status.READY;
        }

        public boolean timedOut() {
            return status == Status.TIMEOUT;
        }
    }

    public enum Status {
        READY,
        TIMEOUT,
        FAILED,
        INTERRUPTED
    }
}
