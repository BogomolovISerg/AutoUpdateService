package git.autoupdateservice.service;

import git.autoupdateservice.domain.ChangedObjectStatus;
import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.domain.RunStatus;
import git.autoupdateservice.domain.Settings;
import git.autoupdateservice.domain.TaskStatus;
import git.autoupdateservice.repo.ChangedObjectRepository;
import git.autoupdateservice.repo.ExecutionRunRepository;
import git.autoupdateservice.repo.LogEventRepository;
import git.autoupdateservice.repo.StepLogBlobRepository;
import git.autoupdateservice.repo.UpdateTaskRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionCleanupService {

    private static final List<TaskStatus> FINAL_TASK_STATUSES = List.of(TaskStatus.UPDATED, TaskStatus.CANCELED);
    private static final List<ChangedObjectStatus> FINAL_CHANGED_OBJECT_STATUSES = List.of(ChangedObjectStatus.UPDATED);
    private static final List<RunStatus> FINAL_RUN_STATUSES = List.of(RunStatus.SUCCESS, RunStatus.FAILED);

    private final UpdateTaskRepository updateTaskRepository;
    private final ChangedObjectRepository changedObjectRepository;
    private final ExecutionRunRepository executionRunRepository;
    private final LogEventRepository logEventRepository;
    private final StepLogBlobRepository stepLogBlobRepository;
    private final RunnerLogsCleanupService runnerLogsCleanupService;
    private final AuditLogService auditLogService;

    @Transactional
    public CleanupSummary cleanup(Settings settings, String actor) {
        int keepDays = SettingsService.normalizeCleanupKeepDays(settings.getCleanupKeepDays(), 30);
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(keepDays);

        DatabaseCleanupSummary database = cleanupDatabase(cutoff);
        RunnerLogsCleanupService.CleanupResult files;
        try {
            files = runnerLogsCleanupService.cleanupOldRuns(keepDays);
        } catch (Exception e) {
            log.warn("Runner logs cleanup failed: {}", e.getMessage(), e);
            files = new RunnerLogsCleanupService.CleanupResult(0);
        }

        CleanupSummary summary = new CleanupSummary(keepDays, cutoff, database, files.deletedDirectories());
        log.info("Retention cleanup completed: {}", summary);

        auditLogService.info(
                LogType.MAINTENANCE,
                "Retention cleanup completed",
                summary.toJson(),
                null,
                actor,
                null
        );
        return summary;
    }

    protected DatabaseCleanupSummary cleanupDatabase(OffsetDateTime cutoff) {
        long deletedStepLogBlobs = stepLogBlobRepository.deleteByEventTsBefore(cutoff);
        long deletedLogEvents = logEventRepository.deleteByTsBefore(cutoff);
        long deletedExecutionRuns = executionRunRepository.deleteByStatusInAndFinishedAtBefore(FINAL_RUN_STATUSES, cutoff);
        long deletedChangedObjects = changedObjectRepository.deleteByStatusInAndLastDetectedAtBefore(FINAL_CHANGED_OBJECT_STATUSES, cutoff);
        long deletedUpdateTasks = updateTaskRepository.deleteByStatusInAndUpdatedAtBefore(FINAL_TASK_STATUSES, cutoff);

        return new DatabaseCleanupSummary(
                deletedUpdateTasks,
                deletedChangedObjects,
                deletedExecutionRuns,
                deletedLogEvents,
                deletedStepLogBlobs
        );
    }

    public record DatabaseCleanupSummary(
            long deletedUpdateTasks,
            long deletedChangedObjects,
            long deletedExecutionRuns,
            long deletedLogEvents,
            long deletedStepLogBlobs
    ) {
    }

    public record CleanupSummary(
            int keepDays,
            OffsetDateTime cutoff,
            DatabaseCleanupSummary database,
            int deletedRunnerLogDirectories
    ) {
        public String toJson() {
            return "{"
                    + "\"keepDays\":" + keepDays
                    + ",\"cutoff\":\"" + cutoff + "\""
                    + ",\"deletedRunnerLogDirectories\":" + deletedRunnerLogDirectories
                    + ",\"deletedUpdateTasks\":" + database.deletedUpdateTasks()
                    + ",\"deletedChangedObjects\":" + database.deletedChangedObjects()
                    + ",\"deletedExecutionRuns\":" + database.deletedExecutionRuns()
                    + ",\"deletedLogEvents\":" + database.deletedLogEvents()
                    + ",\"deletedStepLogBlobs\":" + database.deletedStepLogBlobs()
                    + "}";
        }
    }
}
