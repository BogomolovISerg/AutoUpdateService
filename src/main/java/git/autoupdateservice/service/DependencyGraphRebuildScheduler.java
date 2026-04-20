package git.autoupdateservice.service;

import git.autoupdateservice.domain.DependencyGraphState;
import git.autoupdateservice.domain.LogType;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
public class DependencyGraphRebuildScheduler {

    private final DependencyGraphStateService dependencyGraphStateService;
    private final DependencyGraphRebuildCoordinator dependencyGraphRebuildCoordinator;
    private final AuditLogService auditLogService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${app.scheduler.poll-cron}")
    public void rebuildIfNeeded() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            DependencyGraphState state = dependencyGraphStateService.getState();
            if (state == null || !state.isGraphIsStale()) {
                return;
            }

            auditLogService.info(
                    LogType.WEBHOOK_RECEIVED,
                    "Dependency graph rebuild requested automatically",
                    "{\"staleSince\":" + json(state.getStaleSince() == null ? "" : String.valueOf(state.getStaleSince()))
                            + ",\"staleReason\":" + json(state.getStaleReason() == null ? "" : state.getStaleReason()) + "}",
                    null,
                    "system",
                    null
            );

            dependencyGraphRebuildCoordinator.startIfStaleAsync("scheduled rebuild", null);
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.WEBHOOK_RECEIVED,
                    "Dependency graph rebuild failed: " + safe(e.getMessage()),
                    "{}",
                    null,
                    "system",
                    null
            );
        } finally {
            running.set(false);
        }
    }

    private static String json(String value) {
        String s = value == null ? "" : value;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String s = value.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
