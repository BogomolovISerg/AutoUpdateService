package git.autoupdateservice.service;

import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.domain.Settings;
import git.autoupdateservice.domain.TaskStatus;
import git.autoupdateservice.repo.SettingsRepository;
import git.autoupdateservice.repo.UpdateTaskRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

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

    /**
     * Единая нормализация размеров страниц (чтобы не сломать UI и БД слишком большими значениями).
     */
    public static int normalizePageSize(Integer value, int fallback) {
        int v = (value == null || value <= 0) ? fallback : value;
        if (v < 10) return 10;
        if (v > 500) return 500;
        return v;
    }

    public long pendingNewCount() {
        return updateTaskRepository.countByStatus(TaskStatus.NEW);
    }

    /**
     * Отменяет часть "NEW" задач (для подтверждения выключения автообновления).
     */
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

        s.setAutoUpdateEnabled(patch.isAutoUpdateEnabled());
        s.setTestRunTime(patch.getTestRunTime());
        s.setNextTestRunDate(patch.getNextTestRunDate());
        s.setProductionRunTime(patch.getProductionRunTime());
        s.setNextProductionRunDate(patch.getNextProductionRunDate());
        s.setTimezone(patch.getTimezone());
        s.setClosedMaxAttempts(patch.getClosedMaxAttempts());
        s.setClosedSleepSeconds(patch.getClosedSleepSeconds());

        // pagination
        s.setQueuePageSize(normalizePageSize(patch.getQueuePageSize(), s.getQueuePageSize()));
        s.setLogsPageSize(normalizePageSize(patch.getLogsPageSize(), s.getLogsPageSize()));

        s.setUpdatedAt(OffsetDateTime.now());

        Settings saved = settingsRepository.save(s);

        auditLogService.info(LogType.SETTINGS_CHANGED, "Settings updated",
                "{\"autoUpdateEnabled\":" + saved.isAutoUpdateEnabled() + "}", clientIp, actor, null);

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
}

