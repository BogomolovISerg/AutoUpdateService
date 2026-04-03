package git.autoupdateservice.service;

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
        Settings s = settingsRepository.findById(1L).orElseThrow();
        if (!s.isAutoUpdateEnabled()) return;

        ZoneId zone = ZoneId.of(s.getTimezone());
        LocalDate next = s.getNextRunDate();
        if (next == null) {
            next = LocalDate.now(zone).plusDays(1);
            s.setNextRunDate(next);
            settingsRepository.save(s);
        }

        OffsetDateTime plannedFor = ZonedDateTime.of(next, s.getRunTime(), zone).toOffsetDateTime();
        OffsetDateTime now = OffsetDateTime.now(zone);
        if (now.isBefore(plannedFor)) return;

        updateExecutor.runNightly(plannedFor);

        // автоматический перенос даты на +1 день
        s.setNextRunDate(next.plusDays(1));
        s.setUpdatedAt(OffsetDateTime.now());
        settingsRepository.save(s);
    }
}

