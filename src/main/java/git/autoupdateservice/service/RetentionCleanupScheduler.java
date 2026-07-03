package git.autoupdateservice.service;

import git.autoupdateservice.domain.Settings;
import git.autoupdateservice.repo.SettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
public class RetentionCleanupScheduler {

    private final SettingsRepository settingsRepository;
    private final RetentionCleanupService retentionCleanupService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${app.scheduler.poll-cron}")
    public void tick() {
        Settings settings = settingsRepository.findById(1L).orElse(null);
        if (settings == null) {
            log.warn("Cleanup scheduler tick skipped: settings row id=1 not found");
            return;
        }
        if (!settings.isCleanupEnabled()) {
            log.debug("Cleanup scheduler tick skipped: cleanup disabled");
            return;
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(settings.getTimezone());
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
            log.warn("Invalid cleanup scheduler timezone '{}', using system timezone {}", settings.getTimezone(), zone);
        }

        LocalDate next = settings.getNextCleanupRunDate();
        if (next == null) {
            next = LocalDate.now(zone);
            settings.setNextCleanupRunDate(next);
            settingsRepository.updateNextCleanupRunDate(1L, next, OffsetDateTime.now());
        }

        OffsetDateTime plannedFor = ZonedDateTime.of(next, settings.getCleanupRunTime(), zone).toOffsetDateTime();
        OffsetDateTime now = OffsetDateTime.now(zone);
        if (now.isBefore(plannedFor)) {
            log.debug("Scheduled cleanup is not due yet. now={}, plannedFor={}", now, plannedFor);
            return;
        }

        if (!running.compareAndSet(false, true)) {
            log.info("Scheduled cleanup is already running. plannedFor={}", plannedFor);
            return;
        }

        try {
            log.info("Scheduled cleanup is due. now={}, plannedFor={}", now, plannedFor);
            RetentionCleanupService.CleanupSummary summary = retentionCleanupService.cleanup(settings, "system");
            settings.setNextCleanupRunDate(next.plusDays(1));
            settingsRepository.updateNextCleanupRunDate(1L, next.plusDays(1), OffsetDateTime.now());
            log.info("Scheduled cleanup completed. Next date shifted to {}. Summary={}", next.plusDays(1), summary);
        } catch (Exception e) {
            log.error("Scheduled cleanup failed before completion", e);
        } finally {
            running.set(false);
        }
    }
}
