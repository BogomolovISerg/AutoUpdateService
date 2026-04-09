package git.autoupdateservice.service;

import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.ExecutionRunRepository;
import git.autoupdateservice.repo.SettingsRepository;
import git.autoupdateservice.repo.UpdateTaskRepository;
import git.autoupdateservice.service.steps.RunStepCommandService;
import git.autoupdateservice.service.steps.RunStepDef;
import git.autoupdateservice.service.steps.RunStepExecutor;
import git.autoupdateservice.service.steps.StepPlanLoader;
import git.autoupdateservice.util.PasswordMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class UpdateExecutor {

    private final SettingsRepository settingsRepository;
    private final UpdateTaskRepository updateTaskRepository;
    private final ExecutionRunRepository executionRunRepository;
    private final AuditLogService auditLogService;
    private final StepPlanLoader stepPlanLoader;
    private final RunStepCommandService runStepCommandService;
    private final RunStepExecutor runStepExecutor;
    private final RunnerProperties runnerProperties;

    private final JdbcTemplate jdbcTemplate;
    private final RunnerLogsCleanupService runnerLogsCleanupService;
    private final DependencyGraphStateService dependencyGraphStateService;

    private boolean tryAcquireRunLock() {
        Boolean ok = jdbcTemplate.queryForObject("select pg_try_advisory_lock(987654321)", Boolean.class);
        return Boolean.TRUE.equals(ok);
    }

    private void releaseRunLock() {
        jdbcTemplate.queryForObject("select pg_advisory_unlock(987654321)", Boolean.class);
    }

    public Optional<ExecutionRun> runNightly(OffsetDateTime plannedFor) {

        if (!tryAcquireRunLock()) return Optional.empty();

        try {

            if (executionRunRepository.findByPlannedFor(plannedFor).isPresent()) return Optional.empty();

            DependencyGraphState graphState = dependencyGraphStateService.getState();
            DependencySnapshot activeSnapshot = graphState.getActiveSnapshot();

            ExecutionRun run = new ExecutionRun();
            run.setPlannedFor(plannedFor);
            run.setStartedAt(OffsetDateTime.now());
            run.setStatus(RunStatus.RUNNING);
            run.setDependencySnapshot(activeSnapshot);
            run = executionRunRepository.save(run);

            auditLogService.info(
                    LogType.RUN_STARTED,
                    "Run started for planned_for=" + plannedFor,
                    "{\"runId\":" + j(String.valueOf(run.getId()))
                            + ",\"plannedFor\":" + j(String.valueOf(plannedFor))
                            + ",\"dependencySnapshotId\":" + j(activeSnapshot == null ? "" : String.valueOf(activeSnapshot.getId()))
                            + ",\"graphStale\":" + graphState.isGraphIsStale() + "}",
                    null,
                    "system",
                    run.getId()
            );

            if (activeSnapshot == null) {
                auditLogService.warn(
                        LogType.RUN_STARTED,
                        "Dependency graph snapshot is missing. Run will continue without linked snapshot.",
                        "{\"runId\":" + j(String.valueOf(run.getId())) + ",\"graphStale\":" + graphState.isGraphIsStale() + "}",
                        null,
                        "system",
                        run.getId()
                );
            } else if (graphState.isGraphIsStale()) {
                auditLogService.warn(
                        LogType.RUN_STARTED,
                        "Dependency graph is stale. Run uses last READY snapshot " + activeSnapshot.getId(),
                        "{\"runId\":" + j(String.valueOf(run.getId()))
                                + ",\"dependencySnapshotId\":" + j(String.valueOf(activeSnapshot.getId()))
                                + ",\"staleSince\":" + j(graphState.getStaleSince() == null ? "" : String.valueOf(graphState.getStaleSince()))
                                + ",\"staleReason\":" + j(graphState.getStaleReason() == null ? "" : graphState.getStaleReason()) + "}",
                        null,
                        "system",
                        run.getId()
                );
            }

            try {
                runnerLogsCleanupService.cleanupOldRuns();

                auditLogService.info(
                        LogType.RUN_STARTED,
                        "Runner logs cleanup completed",
                        "{\"stage\":\"cleanupOldRuns\"}",
                        null,
                        "system",
                        run.getId()
                );
            } catch (Exception e) {
                auditLogService.info(
                        LogType.RUN_FAILED,
                        "Runner logs cleanup failed: " + e.getMessage(),
                        "{\"stage\":\"cleanupOldRuns\",\"error\":" + j(e.getMessage()) + "}",
                        null,
                        "system",
                        run.getId()
                );
            }


            Settings s = settingsRepository.findById(1L).orElseThrow();

            List<UpdateTask> tasks = updateTaskRepository.findReadyToRun(TaskStatus.NEW);
            if (tasks.isEmpty()) {
                run.setStatus(RunStatus.SUCCESS);
                run.setFinishedAt(OffsetDateTime.now());
                executionRunRepository.save(run);
                auditLogService.info(LogType.RUN_FINISHED, "Nothing to do", "{\"status\":\"NEW\"}", null, "system", run.getId());
                return Optional.of(run);
            }

            boolean needMain = tasks.stream().anyMatch(t -> t.getTargetType() == TargetType.MAIN);
            List<UpdateTask> extTasks = tasks.stream().filter(t -> t.getTargetType() == TargetType.EXTENSION).toList();
            List<String> extensions = extTasks.stream()
                    .map(UpdateTask::getExtensionName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();

            Path logRoot = Path.of(runnerProperties.logDir());
            Path runDir = logRoot.resolve("run-" + run.getId());
            Path workDir = runDir;

            // Precompute repo paths (from tasks; fallback to runner.* properties if task repo_path is empty)
            String mainRepoPath = null;
            if (needMain) {
                mainRepoPath = tasks.stream()
                        .filter(t -> t.getTargetType() == TargetType.MAIN)
                        .map(UpdateTask::getRepoPath)
                        .filter(rp -> rp != null && !rp.isBlank())
                        .findFirst()
                        .orElse(null);

                if (mainRepoPath == null || mainRepoPath.isBlank()) {
                    mainRepoPath = runnerProperties.mainRepoPath();
                }
            }

            Map<String, String> extRepoByName = new HashMap<>();
            for (UpdateTask t : extTasks) {
                String ext = t.getExtensionName();
                String repo = t.getRepoPath();
                if (ext != null && !ext.isBlank() && repo != null && !repo.isBlank()) {
                    extRepoByName.putIfAbsent(ext, repo);
                }
            }
            List<RunStepDef> plan = stepPlanLoader.loadSteps();

            List<RunStepDef> normalSteps = plan.stream()
                    .filter(RunStepDef::isEnabled)
                    .filter(sd -> !sd.isAlways())
                    .sorted(Comparator.comparingInt(RunStepDef::getOrder))
                    .toList();

            List<RunStepDef> alwaysSteps = plan.stream()
                    .filter(RunStepDef::isEnabled)
                    .filter(RunStepDef::isAlways)
                    .sorted(Comparator.comparingInt(RunStepDef::getOrder))
                    .toList();

            try {
                //for (RunStepDef sd : normalSteps) {
                //    executePlannedStep(run, s, sd, needMain, mainRepoPath, extRepoByName, extensions, runDir, workDir);
                if (!normalSteps.isEmpty()) {
                    RunStepDef firstStep = normalSteps.get(0);
                    executeFirstStepWithRetry(run, s, firstStep, needMain, mainRepoPath, extRepoByName, extensions, runDir, workDir);

                    for (int i = 1; i < normalSteps.size(); i++) {
                        RunStepDef sd = normalSteps.get(i);
                        executePlannedStep(run, s, sd, needMain, mainRepoPath, extRepoByName, extensions, runDir, workDir);
                    }

                }

                OffsetDateTime now = OffsetDateTime.now();
                for (UpdateTask t : tasks) {
                    t.setStatus(TaskStatus.UPDATED);
                    t.setUpdatedAt(now);
                }
                updateTaskRepository.saveAll(tasks);

                run.setStatus(RunStatus.SUCCESS);
                run.setFinishedAt(OffsetDateTime.now());
                executionRunRepository.save(run);

                auditLogService.info(LogType.RUN_FINISHED,
                        "Run finished successfully. tasks=" + tasks.size(),
                        "{\"updatedTasks\":" + tasks.size() + ",\"needMain\":" + needMain + ",\"extensions\":" + extensions.size() + "}",
                        null, "system", run.getId());

                return Optional.of(run);
            } catch (Exception e) {
                run.setStatus(RunStatus.FAILED);
                run.setFinishedAt(OffsetDateTime.now());
                run.setErrorSummary(trim(PasswordMasker.maskText(e.getMessage()), 3500));
                executionRunRepository.save(run);

                auditLogService.error(LogType.RUN_FINISHED, "Run failed: " + e.getMessage(),
                        "{\"error\":" + j(String.valueOf(e.getMessage())) + "}", null, "system", run.getId());

                return Optional.of(run);
            } finally {
                // Always steps (from JSON plan)
                try {
                    Settings s2 = settingsRepository.findById(1L).orElseThrow();
                    for (RunStepDef sd : alwaysSteps) {
                        tryExecutePlannedStepIgnore(run, s2, sd, needMain, mainRepoPath, extRepoByName, extensions, runDir, workDir);
                    }
                } catch (Exception ignored) {}
            }

        } finally {
            releaseRunLock();
        }
    }

    private void executeFirstStepWithRetry(
            ExecutionRun run,
            Settings s,
            RunStepDef firstStep,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            List<String> extensions,
            Path runDir,
            Path workDir
    ) throws Exception {

        final int maxAttempts = 3;
        final int sleepSeconds = Math.max(1, s.getClosedSleepSeconds());
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (attempt > 1) {
                    auditLogService.info(
                            LogType.RUN_STARTED,
                            "Retrying run after first step failure. Attempt " + attempt + "/" + maxAttempts,
                            "{\"stepCode\":" + j(firstStep.getCode()) + ",\"attempt\":" + attempt + ",\"maxAttempts\":" + maxAttempts + "}",
                            null,
                            "system",
                            run.getId()
                    );
                }

                executePlannedStep(run, s, firstStep, needMain, mainRepoPath, extRepoByName, extensions, runDir, workDir);
                return;
            } catch (Exception e) {
                lastError = e;
                if (attempt >= maxAttempts) {
                    throw e;
                }

                auditLogService.warn(
                        LogType.STEP_FAILED,
                        "First step failed, waiting before retry: " + e.getMessage(),
                        "{\"stepCode\":" + j(firstStep.getCode()) + ",\"attempt\":" + attempt + ",\"maxAttempts\":" + maxAttempts + ",\"sleepSeconds\":" + sleepSeconds + ",\"error\":" + j(String.valueOf(e.getMessage())) + "}",
                        null,
                        "system",
                        run.getId()
                );

                try {
                    Thread.sleep(sleepSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }
    }
    private void executePlannedStep(
            ExecutionRun run,
            Settings s,
            RunStepDef sd,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            List<String> extensions,
            Path runDir,
            Path workDir
    ) throws Exception {

        if (sd == null || !sd.isEnabled()) return;

        String condition = norm(sd.getCondition());
        if ("needmain".equals(condition) && !needMain) {
            auditLogService.info(LogType.STEP_FINISHED,
                    "Skip step by condition needMain=false: " + sd.getTitle(),
                    "{\"code\":" + j(sd.getCode()) + ",\"title\":" + j(sd.getTitle()) + "}", null, "system", run.getId());
            return;
        }

        String foreach = norm(sd.getForeach());
        if ("extensions".equals(foreach)) {
            for (String ext : extensions) {
                executePlannedStepSingle(run, s, sd, needMain, mainRepoPath, extRepoByName, runDir, workDir, ext);
            }
            return;
        }

        executePlannedStepSingle(run, s, sd, needMain, mainRepoPath, extRepoByName, runDir, workDir, null);
    }

    private void tryExecutePlannedStepIgnore(
            ExecutionRun run,
            Settings s,
            RunStepDef sd,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            List<String> extensions,
            Path runDir,
            Path workDir
    ) {
        try {
            executePlannedStep(run, s, sd, needMain, mainRepoPath, extRepoByName, extensions, runDir, workDir);
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.STEP_FAILED,
                    "Ignore failed planned step: " + sd.getTitle() + " (" + e.getMessage() + ")",
                    "{\"code\":" + j(sd.getCode()) + ",\"error\":" + j(String.valueOf(e.getMessage())) + "}",
                    null, "system", run.getId()
            );
        }
    }

    private void executePlannedStepSingle(
            ExecutionRun run,
            Settings s,
            RunStepDef sd,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            Path runDir,
            Path workDir,
            String ext
    ) throws Exception {

        String extFile = (ext == null) ? null : safeFileName(ext);
        String extRepoPath = (ext == null) ? null : extRepoByName.get(ext);
        if (ext != null && (extRepoPath == null || extRepoPath.isBlank())) {
            // fallback to runner.extRepoPath (single repo for all extensions)
            extRepoPath = runnerProperties.extRepoPath();
        }
        if (ext != null && (extRepoPath == null || extRepoPath.isBlank())) {
            throw new IllegalStateException("Repo path not found for extension=" + ext + " (task.repoPath empty and runner.extRepoPath not set)");
        }

        // Важно: команды статичны и задаются в JSON. Здесь только подстановка токенов.
        if (sd.getRetry() != null && sd.getRetry().getCheckCommand() != null && !sd.getRetry().getCheckCommand().isEmpty()) {
            runRetryStep(run, s, sd, needMain, mainRepoPath, extRepoPath, ext, extFile, runDir, workDir);
            return;
        }

        if (sd.getCommand() == null || sd.getCommand().isEmpty()) {
            throw new IllegalArgumentException("Step command is empty. code=" + sd.getCode());
        }

        Map<String, String> ctx = runStepCommandService.buildContext(
                run,
                s,
                needMain,
                mainRepoPath,
                extRepoPath,
                ext,
                extFile,
                null,
                runDir,
                workDir
        );

        String code = firstNonBlank(runStepCommandService.render(sd.getCode(), ctx), "STEP_" + sd.getOrder());
        String title = firstNonBlank(runStepCommandService.render(sd.getTitle(), ctx), code);

        List<String> command = runStepCommandService.expandCommand(sd.getCommand(), ctx, true);

        runStepExecutor.execute(run, code, title, command, workDir);
    }

    private void runRetryStep(
            ExecutionRun run,
            Settings s,
            RunStepDef sd,
            boolean needMain,
            String mainRepoPath,
            String extRepoPath,
            String ext,
            String extFile,
            Path runDir,
            Path workDir
    ) throws Exception {

        RunStepDef.RetryDef r = sd.getRetry();
        int maxAttempts = (r.getMaxAttempts() > 0) ? r.getMaxAttempts() : s.getClosedMaxAttempts();
        int sleepSeconds = (r.getSleepSeconds() > 0) ? r.getSleepSeconds() : s.getClosedSleepSeconds();

        if (maxAttempts <= 0) maxAttempts = 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Map<String, String> ctx = runStepCommandService.buildContext(
                    run,
                    s,
                    needMain,
                    mainRepoPath,
                    extRepoPath,
                    ext,
                    extFile,
                    attempt,
                    runDir,
                    workDir
            );

            String baseCode = firstNonBlank(runStepCommandService.render(sd.getCode(), ctx), "RETRY_" + sd.getOrder());
            String baseTitle = firstNonBlank(runStepCommandService.render(sd.getTitle(), ctx), baseCode);

            String checkCode = baseCode + "_TRY" + attempt;
            String checkTitle = baseTitle + " (попытка " + attempt + "/" + maxAttempts + ")";

            List<String> checkCmd = runStepCommandService.expandCommand(r.getCheckCommand(), ctx, true);

            int exit = runStepExecutor.executeAllowFailure(run, checkCode, checkTitle, checkCmd, workDir);
            if (exit == 0) return;

            // onFail: например session kill (не валим весь запуск, просто логируем; дальше будет sleep и новая попытка)
            if (r.getOnFailCommand() != null && !r.getOnFailCommand().isEmpty()) {
                String failCode = baseCode + "_ONFAIL" + attempt;
                String failTitle = baseTitle + " (onFail, попытка " + attempt + ")";
                List<String> failCmd = runStepCommandService.expandCommand(r.getOnFailCommand(), ctx, true);
                runStepExecutor.executeAllowFailure(run, failCode, failTitle, failCmd, workDir);
            }

            if (attempt < maxAttempts && sleepSeconds > 0) {
                try {
                    Thread.sleep(sleepSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }

        throw new IllegalStateException(firstNonBlank(sd.getTitle(), sd.getCode()) + " failed after " + maxAttempts + " attempts");
    }

    private static String j(String s) {
        if (s == null) return "\"\"";
        String x = s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
        return "\"" + x + "\"";
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static String firstNonBlank(String a, String b, String c) {
        String x = firstNonBlank(a, b);
        return (x != null && !x.isBlank()) ? x : c;
    }

    private static String safeFileName(String s) {
        if (s == null) return "null";
        String x = s.trim();
        x = x.replaceAll("[^0-9A-Za-zА-Яа-я._-]+", "_");
        if (x.length() > 60) x = x.substring(0, 60);
        if (x.isBlank()) return "ext";
        return x;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

}
