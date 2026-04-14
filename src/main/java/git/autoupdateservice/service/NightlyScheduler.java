package git.autoupdateservice.service;

import git.autoupdateservice.domain.RunStage;
import git.autoupdateservice.domain.Settings;
import git.autoupdateservice.repo.SettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;

@Component
@RequiredArgsConstructor
public class NightlyScheduler {

    private final SettingsRepository settingsRepository;
    private final UpdateExecutor updateExecutor;

    @Scheduled(cron = "${app.scheduler.poll-cron}")
    public void tick() {
        Settings s = settingsRepository.findById(1L).orElse(null);
        if (s == null || !s.isAutoUpdateEnabled()) return;

        ZoneId zone;
        try {
            zone = ZoneId.of(s.getTimezone());
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }

        scheduleIfDue(s, zone, RunStage.TEST);
        scheduleIfDue(s, zone, RunStage.PRODUCTION);
    }

    private void scheduleIfDue(Settings settings, ZoneId zone, RunStage stage) {
        LocalDate next = nextDate(settings, stage);
        if (next == null) {
            next = LocalDate.now(zone).plusDays(1);
            setNextDate(settings, stage, next);
            settingsRepository.save(settings);
        }

        OffsetDateTime plannedFor = ZonedDateTime.of(next, runTime(settings, stage), zone).toOffsetDateTime();
        OffsetDateTime now = OffsetDateTime.now(zone);
        if (now.isBefore(plannedFor)) {
            return;
        }

        updateExecutor.runScheduled(stage, plannedFor);

        setNextDate(settings, stage, next.plusDays(1));
        settings.setUpdatedAt(OffsetDateTime.now());
        settingsRepository.save(settings);
    }

    private LocalDate nextDate(Settings settings, RunStage stage) {
        return stage == RunStage.TEST ? settings.getNextTestRunDate() : settings.getNextProductionRunDate();
    }

    private LocalTime runTime(Settings settings, RunStage stage) {
        return stage == RunStage.TEST ? settings.getTestRunTime() : settings.getProductionRunTime();
    }

    private void setNextDate(Settings settings, RunStage stage, LocalDate value) {
        if (stage == RunStage.TEST) {
            settings.setNextTestRunDate(value);
        } else {
            settings.setNextProductionRunDate(value);
        }
    }
}

