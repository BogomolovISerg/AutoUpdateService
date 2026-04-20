package git.autoupdateservice.service;

import git.autoupdateservice.domain.ExecutionRun;
import git.autoupdateservice.domain.RunStage;
import git.autoupdateservice.domain.RunStatus;
import git.autoupdateservice.domain.Settings;
import git.autoupdateservice.repo.SettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class NightlyScheduler {

    private final SettingsRepository settingsRepository;
    private final UpdateExecutor updateExecutor;
    private final Instant schedulerStartedAt = Instant.now();

    @Scheduled(cron = "${app.scheduler.poll-cron}")
    public void tick() {
        Settings s = settingsRepository.findById(1L).orElse(null);
        if (s == null) {
            log.warn("Scheduler tick skipped: settings row id=1 not found");
            return;
        }
        if (!s.isAutoUpdateEnabled()) {
            log.debug("Scheduler tick skipped: auto update disabled");
            return;
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(s.getTimezone());
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
            log.warn("Invalid scheduler timezone '{}', using system timezone {}", s.getTimezone(), zone);
        }

        ScheduleResult testResult = tryScheduleStage(s, zone, RunStage.TEST);
        if (testResult.due() && !testResult.success()) {
            log.warn("Scheduled PRODUCTION run skipped because due TEST run did not finish successfully. testStatus={}", testResult.status());
            if (testResult.shouldShiftProduction()) {
                Settings refreshed = settingsRepository.findById(1L).orElse(s);
                shiftProductionAfterBlockedTest(refreshed, zone);
            }
            return;
        }

        Settings refreshed = settingsRepository.findById(1L).orElse(s);
        tryScheduleStage(refreshed, zone, RunStage.PRODUCTION);
    }

    private ScheduleResult tryScheduleStage(Settings settings, ZoneId zone, RunStage stage) {
        try {
            return scheduleIfDue(settings, zone, stage);
        } catch (Exception e) {
            log.error("Scheduled {} run failed before completion", stage, e);
            return ScheduleResult.failedBeforeRun();
        }
    }

    private ScheduleResult scheduleIfDue(Settings settings, ZoneId zone, RunStage stage) {
        LocalDate next = nextDate(settings, stage);
        if (next == null) {
            next = LocalDate.now(zone);
            setNextDate(settings, stage, next);
            settingsRepository.save(settings);
        }

        OffsetDateTime plannedFor = ZonedDateTime.of(next, runTime(settings, stage), zone).toOffsetDateTime();
        OffsetDateTime now = OffsetDateTime.now(zone);
        if (now.isBefore(plannedFor)) {
            log.debug("Scheduled {} run is not due yet. now={}, plannedFor={}", stage, now, plannedFor);
            return ScheduleResult.notDue();
        }
        if (plannedFor.toInstant().isBefore(schedulerStartedAt)) {
            shiftNextDate(settings, stage, next);
            log.warn(
                    "Scheduled {} run skipped because planned time is before application startup. plannedFor={}, schedulerStartedAt={}. Next date shifted to {}",
                    stage,
                    plannedFor,
                    schedulerStartedAt,
                    next.plusDays(1)
            );
            return ScheduleResult.skippedCatchUp();
        }

        log.info("Scheduled {} run is due. now={}, plannedFor={}", stage, now, plannedFor);
        Optional<ExecutionRun> result = updateExecutor.runScheduled(stage, plannedFor);
        if (result.isEmpty()) {
            log.info("Scheduled {} run was not started. The advisory lock is busy. plannedFor={}", stage, plannedFor);
            return ScheduleResult.lockBusy();
        }

        ExecutionRun run = result.get();
        if (run.getStatus() == RunStatus.RUNNING) {
            log.info("Scheduled {} run is already running for {}", stage, plannedFor);
            return ScheduleResult.running();
        }

        if (run.getStatus() != RunStatus.SUCCESS) {
            log.warn("Scheduled {} run finished with status {} for {}", stage, run.getStatus(), plannedFor);
            shiftNextDate(settings, stage, next);
            return ScheduleResult.completed(run.getStatus());
        }

        shiftNextDate(settings, stage, next);
        log.info("Scheduled {} run succeeded. Next date shifted to {}", stage, next.plusDays(1));
        return ScheduleResult.completed(RunStatus.SUCCESS);
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

    private void shiftNextDate(Settings settings, RunStage stage, LocalDate currentDate) {
        setNextDate(settings, stage, currentDate.plusDays(1));
        settings.setUpdatedAt(OffsetDateTime.now());
        settingsRepository.save(settings);
    }

    private void shiftProductionAfterBlockedTest(Settings settings, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        LocalDate productionDate = settings.getNextProductionRunDate();
        if (productionDate == null) {
            productionDate = today;
        }

        if (productionDate.isAfter(today)) {
            return;
        }

        LocalDate shifted = today.plusDays(1);
        settings.setNextProductionRunDate(shifted);
        settings.setUpdatedAt(OffsetDateTime.now());
        settingsRepository.save(settings);
        log.warn("Scheduled PRODUCTION date shifted to {} because TEST did not complete successfully", shifted);
    }

    private record ScheduleResult(boolean due, RunStatus status, boolean success, boolean shouldShiftProduction) {
        static ScheduleResult notDue() {
            return new ScheduleResult(false, null, false, false);
        }

        static ScheduleResult lockBusy() {
            return new ScheduleResult(true, RunStatus.RUNNING, false, false);
        }

        static ScheduleResult running() {
            return new ScheduleResult(true, RunStatus.RUNNING, false, false);
        }

        static ScheduleResult failedBeforeRun() {
            return new ScheduleResult(true, RunStatus.FAILED, false, true);
        }

        static ScheduleResult skippedCatchUp() {
            return new ScheduleResult(true, null, false, true);
        }

        static ScheduleResult completed(RunStatus status) {
            return new ScheduleResult(true, status, status == RunStatus.SUCCESS, status == RunStatus.FAILED);
        }
    }
}

