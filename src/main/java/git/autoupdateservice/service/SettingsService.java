package git.autoupdateservice.service;

import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.domain.Settings;
import git.autoupdateservice.domain.TaskStatus;
import git.autoupdateservice.repo.SettingsRepository;
import git.autoupdateservice.repo.UpdateTaskRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SettingsRepository settingsRepository;
    private final UpdateTaskRepository updateTaskRepository;
    private final AuditLogService auditLogService;

    public Settings get() {
        return settingsRepository.findById(1L).orElseGet(() -> {
            Settings settings = new Settings();
            settings.setId(1L);
            settings.setUpdatedAt(OffsetDateTime.now());
            return settingsRepository.save(settings);
        });
    }

    public static int normalizePageSize(Integer value, int fallback) {
        int v = (value == null || value <= 0) ? fallback : value;
        if (v < 10) return 10;
        if (v > 500) return 500;
        return v;
    }

    public static int normalizeCleanupKeepDays(Integer value, int fallback) {
        int v = (value == null || value <= 0) ? fallback : value;
        if (v < 1) return 1;
        if (v > 3650) return 3650;
        return v;
    }

    public static int normalizeDependencyGraphRebuildWaitMinutes(Integer value, int fallback) {
        int v = (value == null || value <= 0) ? fallback : value;
        if (v < 1) return 1;
        if (v > 1440) return 1440;
        return v;
    }

    public long pendingNewCount() {
        return updateTaskRepository.countByStatus(TaskStatus.NEW);
    }

    @Transactional
    public void cancelPendingNewTasks(String clientIp, String actor) {
        var tasks = updateTaskRepository.findTop200ByStatusOrderByCreatedAtDesc(TaskStatus.NEW);
        var now = OffsetDateTime.now();
        for (var t : tasks) {
            t.setStatus(TaskStatus.CANCELED);
            t.setUpdatedAt(now);
        }
        updateTaskRepository.saveAll(tasks);

        auditLogService.info(LogType.TASK_STATUS_CHANGED,
                "Pending NEW tasks canceled due to disabling auto update",
                "{\"count\":" + tasks.size() + "}", clientIp, actor, null);
    }

    @Transactional
    public Settings update(Settings patch, String clientIp, String actor) {
        Settings s = get();
        LocalTime previousTestRunTime = s.getTestRunTime();
        LocalTime previousProductionRunTime = s.getProductionRunTime();
        LocalTime previousCleanupRunTime = s.getCleanupRunTime();

        String timezone = hasText(patch.getTimezone()) ? patch.getTimezone() : s.getTimezone();
        ZoneId zone = resolveZone(timezone);
        LocalTime testRunTime = firstNonNull(patch.getTestRunTime(), s.getTestRunTime());
        LocalTime productionRunTime = firstNonNull(patch.getProductionRunTime(), s.getProductionRunTime());
        LocalTime cleanupRunTime = firstNonNull(patch.getCleanupRunTime(), s.getCleanupRunTime());

        s.setAutoUpdateEnabled(patch.isAutoUpdateEnabled());
        s.setDependencyGraphRebuildEnabled(patch.isDependencyGraphRebuildEnabled());
        s.setDependencyGraphRebuildWaitMinutes(normalizeDependencyGraphRebuildWaitMinutes(
                patch.getDependencyGraphRebuildWaitMinutes(),
                s.getDependencyGraphRebuildWaitMinutes()
        ));
        s.setIgnoreTestResults(patch.isIgnoreTestResults());
        s.setTestRunTime(testRunTime);
        s.setNextTestRunDate(normalizeNextRunDate(patch.getNextTestRunDate(), testRunTime, previousTestRunTime, zone));
        s.setProductionRunTime(productionRunTime);
        s.setNextProductionRunDate(normalizeNextRunDate(patch.getNextProductionRunDate(), productionRunTime, previousProductionRunTime, zone));
        s.setCleanupEnabled(patch.isCleanupEnabled());
        s.setCleanupRunTime(cleanupRunTime);
        s.setNextCleanupRunDate(normalizeNextRunDate(patch.getNextCleanupRunDate(), cleanupRunTime, previousCleanupRunTime, zone));
        s.setCleanupKeepDays(normalizeCleanupKeepDays(patch.getCleanupKeepDays(), s.getCleanupKeepDays()));
        s.setTimezone(timezone);
        if (hasText(patch.getLockMessage())) {
            s.setLockMessage(patch.getLockMessage());
        }
        if (hasText(patch.getUccode())) {
            s.setUccode(patch.getUccode());
        }
        s.setClosedMaxAttempts(patch.getClosedMaxAttempts());
        s.setClosedSleepSeconds(patch.getClosedSleepSeconds());

        s.setQueuePageSize(normalizePageSize(patch.getQueuePageSize(), s.getQueuePageSize()));
        s.setLogsPageSize(normalizePageSize(patch.getLogsPageSize(), s.getLogsPageSize()));

        s.setUpdatedAt(OffsetDateTime.now());

        Settings saved = settingsRepository.save(s);

        auditLogService.info(LogType.SETTINGS_CHANGED, "Settings updated",
                "{\"autoUpdateEnabled\":" + saved.isAutoUpdateEnabled()
                        + ",\"dependencyGraphRebuildEnabled\":" + saved.isDependencyGraphRebuildEnabled()
                        + ",\"dependencyGraphRebuildWaitMinutes\":" + saved.getDependencyGraphRebuildWaitMinutes()
                        + ",\"ignoreTestResults\":" + saved.isIgnoreTestResults()
                        + ",\"nextTestRunDate\":\"" + saved.getNextTestRunDate()
                        + "\",\"testRunTime\":\"" + saved.getTestRunTime()
                        + "\",\"nextProductionRunDate\":\"" + saved.getNextProductionRunDate()
                        + "\",\"productionRunTime\":\"" + saved.getProductionRunTime() + "\"}",
                clientIp, actor, null);

        return saved;
    }

    @Transactional
    public void setAutoUpdateEnabled(boolean enabled, boolean cancelPending, String clientIp, String actor) {
        Settings s = get();
        boolean before = s.isAutoUpdateEnabled();

        s.setAutoUpdateEnabled(enabled);
        s.setUpdatedAt(OffsetDateTime.now());
        settingsRepository.save(s);

        auditLogService.info(LogType.AUTO_UPDATE_TOGGLED,
                "Auto update toggled: " + before + " -> " + enabled,
                "{\"cancelPending\":" + cancelPending + "}", clientIp, actor, null);

        if (!enabled && cancelPending) {
            cancelPendingNewTasks(clientIp, actor);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ZoneId resolveZone(String value) {
        try {
            return hasText(value) ? ZoneId.of(value) : ZoneId.systemDefault();
        } catch (DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }

    private static LocalDate normalizeNextRunDate(
            LocalDate requestedDate,
            LocalTime requestedTime,
            LocalTime previousTime,
            ZoneId zone
    ) {
        LocalDate today = LocalDate.now(zone);
        LocalDate result = requestedDate == null ? today : requestedDate;
        if (result.isBefore(today)) {
            result = today;
        }

        if (requestedTime != null && !Objects.equals(previousTime, requestedTime)) {
            LocalTime now = LocalTime.now(zone);
            if (!requestedTime.isBefore(now)) {
                result = today;
            }
        }

        return result;
    }

    private static LocalTime firstNonNull(LocalTime value, LocalTime fallback) {
        return value == null ? fallback : value;
    }
}

