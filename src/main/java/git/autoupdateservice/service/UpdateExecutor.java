package git.autoupdateservice.service;

import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.ExecutionRunRepository;
import git.autoupdateservice.repo.SettingsRepository;
import git.autoupdateservice.repo.UpdateTaskRepository;
import git.autoupdateservice.service.steps.RunPlan;
import git.autoupdateservice.service.steps.RunStepCommandService;
import git.autoupdateservice.service.steps.RunStepDef;
import git.autoupdateservice.service.steps.RunStepExecutor;
import git.autoupdateservice.service.steps.StepPlanLoader;
import git.autoupdateservice.util.PasswordMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
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
    private final DependencyGraphStateService dependencyGraphStateService;
    private final DependencyGraphRebuildCoordinator dependencyGraphRebuildCoordinator;
    private final ChangedObjectService changedObjectService;
    private final SmokeTestConfigService smokeTestConfigService;

    private Connection tryAcquireRunLock() {
        try {
            if (jdbcTemplate.getDataSource() == null) {
                throw new IllegalStateException("DataSource is not available for advisory lock");
            }
            Connection connection = jdbcTemplate.getDataSource().getConnection();
            try (PreparedStatement ps = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                ps.setLong(1, 987654321L);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean locked = rs.next() && rs.getBoolean(1);
                    if (locked) {
                        return connection;
                    }
                }
            }
            connection.close();
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot acquire run advisory lock", e);
        }
    }

    private void releaseRunLock(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            try (PreparedStatement ps = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                ps.setLong(1, 987654321L);
                try (ResultSet ignored = ps.executeQuery()) {
                    // Advisory locks are session-bound, so unlock on the same connection that acquired it.
                }
            }
        } catch (SQLException ignored) {
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    public Optional<ExecutionRun> runScheduled(RunStage stage, OffsetDateTime plannedFor) {

        Connection runLockConnection = tryAcquireRunLock();
        if (runLockConnection == null) return Optional.empty();

        try {

            Optional<ExecutionRun> existingRun = executionRunRepository.findTopByPlannedForAndStageOrderByStartedAtDesc(plannedFor, stage);
            if (existingRun.isPresent()) {
                ExecutionRun previousRun = existingRun.get();
                if (previousRun.getStatus() != RunStatus.RUNNING) {
                    return existingRun;
                }

                previousRun.setStatus(RunStatus.FAILED);
                previousRun.setFinishedAt(OffsetDateTime.now());
                previousRun.setErrorSummary("Предыдущий запуск был в статусе RUNNING, но активной advisory-блокировки нет. Запуск помечен как FAILED перед повтором.");
                executionRunRepository.save(previousRun);
                auditLogService.warn(
                        LogType.RUN_FINISHED,
                        "Предыдущий регламентный запуск был в статусе RUNNING, но активной advisory-блокировки нет. Запуск помечен как FAILED перед повтором.",
                        "{\"stage\":" + j(stage.name()) + ",\"plannedFor\":" + j(String.valueOf(plannedFor)) + ",\"previousRunId\":" + j(String.valueOf(previousRun.getId())) + "}",
                        null,
                        "system",
                        previousRun.getId()
                );
            }

            DependencyGraphState graphState = dependencyGraphStateService.getState();
            DependencySnapshot activeSnapshot = graphState.getActiveSnapshot();

            ExecutionRun run = new ExecutionRun();
            run.setPlannedFor(plannedFor);
            run.setStage(stage);
            run.setStartedAt(OffsetDateTime.now());
            run.setStatus(RunStatus.RUNNING);
            run.setDependencySnapshot(activeSnapshot);
            run = executionRunRepository.save(run);

            auditLogService.info(
                    LogType.RUN_STARTED,
                    "Run started for planned_for=" + plannedFor,
                    "{\"runId\":" + j(String.valueOf(run.getId()))
                            + ",\"stage\":" + j(stage.name())
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

            Settings s = settingsRepository.findById(1L).orElseThrow();
            RunPlan plan = stepPlanLoader.loadPlan(stage);

            List<UpdateTask> tasks = loadTasksForStage(stage);
            if (tasks.isEmpty()) {
                run.setStatus(RunStatus.SUCCESS);
                run.setFinishedAt(OffsetDateTime.now());
                executionRunRepository.save(run);
                auditLogService.info(LogType.RUN_FINISHED, "Nothing to do", "{\"stage\":" + j(stage.name()) + "}", null, "system", run.getId());
                return Optional.of(run);
            }

            if (stage == RunStage.TEST) {
                graphState = prepareDependencyGraphForTestRun(run, s, graphState);
                activeSnapshot = graphState == null ? null : graphState.getActiveSnapshot();
            }

            boolean needMain = tasks.stream().anyMatch(t -> t.getTargetType() == TargetType.MAIN);
            List<UpdateTask> extTasks = tasks.stream().filter(t -> t.getTargetType() == TargetType.EXTENSION).toList();
            List<String> extensions = resolveExtensions(extTasks);
            Map<String, String> extPlanFileKeyByName = resolveExtensionPlanFileKeys(extTasks);

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
                    mainRepoPath = firstNonBlank(
                            runStepCommandService.planValue(plan.getSettings(), "mainRepoPath", "main-repo-path"),
                            runnerProperties.mainRepoPath()
                    );
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

            List<RunStepDef> orderedSteps = collectOrderedSteps(plan);
            List<RunStepDef> alwaysSteps = collectAlwaysSteps(plan);
            Set<RunStepDef> executedAlwaysSteps = new LinkedHashSet<>();

            try {
                if (stage == RunStage.PRODUCTION) {
                    validateSmokeStatusBeforeProductionRun(run, s, plan);
                }

                if (stage == RunStage.TEST) {
                    Path smokeConfigFile = smokeTestConfigService.generateForTesting(plan, run, workDir);
                    plan.getSettings().put("xunitConfigFile", smokeConfigFile.toString());
                    plan.getSettings().put("xunit-config-file", smokeConfigFile.toString());
                    plan.getSettings().put("smokeConfigFile", smokeConfigFile.toString());
                    plan.getSettings().put("smoke-config-file", smokeConfigFile.toString());
                    smokeTestConfigService.prepareStatusFileForTestRun(plan);
                }

                executePlanSteps(run, s, plan, orderedSteps, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, extensions, runDir, workDir, true, executedAlwaysSteps);

                if (stage == RunStage.TEST) {
                    verifyTestResult(run, s, plan);
                }

                OffsetDateTime now = OffsetDateTime.now();
                for (UpdateTask t : tasks) {
                    t.setStatus(successStatus(stage));
                    t.setUpdatedAt(now);
                }
                updateTaskRepository.saveAll(tasks);
                afterStageSuccess(stage);

                run.setStatus(RunStatus.SUCCESS);
                run.setFinishedAt(OffsetDateTime.now());
                executionRunRepository.save(run);

                auditLogService.info(LogType.RUN_FINISHED,
                        "Run finished successfully. tasks=" + tasks.size(),
                        "{\"stage\":" + j(stage.name()) + ",\"updatedTasks\":" + tasks.size() + ",\"needMain\":" + needMain + ",\"extensions\":" + extensions.size() + "}",
                        null, "system", run.getId());

                return Optional.of(run);
            } catch (Exception e) {
                afterStageFailure(stage, tasks);
                run.setStatus(RunStatus.FAILED);
                run.setFinishedAt(OffsetDateTime.now());
                run.setErrorSummary(trim(PasswordMasker.maskText(e.getMessage()), 3500));
                executionRunRepository.save(run);

                auditLogService.error(LogType.RUN_FINISHED, "Run failed: " + e.getMessage(),
                        "{\"stage\":" + j(stage.name()) + ",\"error\":" + j(String.valueOf(e.getMessage())) + "}", null, "system", run.getId());

                return Optional.of(run);
            } finally {
                // Always steps (from JSON plan)
                try {
                    Settings s2 = settingsRepository.findById(1L).orElseThrow();
                    for (RunStepDef sd : alwaysSteps) {
                        if (!executedAlwaysSteps.contains(sd)) {
                            tryExecutePlannedStepIgnore(run, s2, plan, sd, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, extensions, runDir, workDir);
                        }
                    }
                } catch (Exception ignored) {}
            }

        } finally {
            releaseRunLock(runLockConnection);
        }
    }

    private void executeFirstStepWithRetry(
            ExecutionRun run,
            Settings s,
            RunPlan plan,
            RunStepDef firstStep,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            Map<String, String> extPlanFileKeyByName,
            List<String> extensions,
            Path runDir,
            Path workDir,
            boolean allowSpecialExtensionPlans,
            Set<RunStepDef> executedAlwaysSteps
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

                executeConfiguredStep(run, s, plan, firstStep, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, extensions, runDir, workDir, allowSpecialExtensionPlans, executedAlwaysSteps);
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

    private void executePlanSteps(
            ExecutionRun run,
            Settings s,
            RunPlan plan,
            List<RunStepDef> orderedSteps,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            Map<String, String> extPlanFileKeyByName,
            List<String> extensions,
            Path runDir,
            Path workDir,
            boolean allowSpecialExtensionPlans,
            Set<RunStepDef> executedAlwaysSteps
    ) throws Exception {
        if (orderedSteps == null || orderedSteps.isEmpty()) {
            return;
        }

        RunStepDef firstStep = orderedSteps.get(0);
        executeFirstStepWithRetry(run, s, plan, firstStep, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, extensions, runDir, workDir, allowSpecialExtensionPlans, executedAlwaysSteps);

        for (int i = 1; i < orderedSteps.size(); i++) {
            RunStepDef sd = orderedSteps.get(i);
            executeConfiguredStep(run, s, plan, sd, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, extensions, runDir, workDir, allowSpecialExtensionPlans, executedAlwaysSteps);
        }
    }

    private void executeExtensionPlans(
            ExecutionRun run,
            Settings s,
            RunPlan basePlan,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            Map<String, String> extPlanFileKeyByName,
            List<String> extensions,
            Path runDir,
            Path workDir
    ) throws Exception {
        for (String ext : extensions) {
            String extFile = resolveExtensionPlanFileKey(extPlanFileKeyByName, ext);
            RunPlan extPlan = stepPlanLoader.loadExtensionPlan(basePlan, ext, extFile)
                    .orElseThrow(() -> new IllegalStateException("Extension plan pattern is not configured for extension=" + ext));
            Map<String, String> singleExtFileKey = Map.of(ext, extFile);

            List<RunStepDef> orderedSteps = collectOrderedSteps(extPlan);
            List<RunStepDef> alwaysSteps = collectAlwaysSteps(extPlan);
            Set<RunStepDef> executedAlwaysSteps = new LinkedHashSet<>();

            try {
                executePlanSteps(run, s, extPlan, orderedSteps, needMain, mainRepoPath, extRepoByName, singleExtFileKey, List.of(ext), runDir, workDir, false, executedAlwaysSteps);
                verifyTestResult(run, s, extPlan);
            } finally {
                for (RunStepDef sd : alwaysSteps) {
                    if (!executedAlwaysSteps.contains(sd)) {
                        tryExecutePlannedStepIgnore(run, s, extPlan, sd, needMain, mainRepoPath, extRepoByName, singleExtFileKey, List.of(ext), runDir, workDir);
                    }
                }
            }
        }
    }

    private List<RunStepDef> collectOrderedSteps(RunPlan plan) {
        List<RunStepDef> steps = plan == null || plan.getSteps() == null ? List.of() : plan.getSteps();
        return steps.stream()
                .filter(RunStepDef::isEnabled)
                .sorted(Comparator.comparingInt(RunStepDef::getOrder))
                .toList();
    }

    private List<RunStepDef> collectAlwaysSteps(RunPlan plan) {
        List<RunStepDef> steps = plan == null || plan.getSteps() == null ? List.of() : plan.getSteps();
        return steps.stream()
                .filter(RunStepDef::isEnabled)
                .filter(RunStepDef::isAlways)
                .sorted(Comparator.comparingInt(RunStepDef::getOrder))
                .toList();
    }

    private void executeConfiguredStep(
            ExecutionRun run,
            Settings s,
            RunPlan plan,
            RunStepDef sd,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            Map<String, String> extPlanFileKeyByName,
            List<String> extensions,
            Path runDir,
            Path workDir,
            boolean allowSpecialExtensionPlans,
            Set<RunStepDef> executedAlwaysSteps
    ) throws Exception {
        if (isExtensionPlansStep(sd)) {
            if (!allowSpecialExtensionPlans) {
                throw new IllegalStateException("Special step extensionPlans is not supported inside extension plan");
            }
            if (!extensions.isEmpty()) {
                executeExtensionPlans(run, s, plan, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, extensions, runDir, workDir);
            }
            if (sd.isAlways()) {
                executedAlwaysSteps.add(sd);
            }
            return;
        }

        executePlannedStep(run, s, plan, sd, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, extensions, runDir, workDir);
        if (sd.isAlways()) {
            executedAlwaysSteps.add(sd);
        }
    }
    private void executePlannedStep(
            ExecutionRun run,
            Settings s,
            RunPlan plan,
            RunStepDef sd,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            Map<String, String> extPlanFileKeyByName,
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
                executePlannedStepSingle(run, s, plan, sd, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, runDir, workDir, ext);
            }
            return;
        }

        executePlannedStepSingle(run, s, plan, sd, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, runDir, workDir, null);
    }

    private void tryExecutePlannedStepIgnore(
            ExecutionRun run,
            Settings s,
            RunPlan plan,
            RunStepDef sd,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            Map<String, String> extPlanFileKeyByName,
            List<String> extensions,
            Path runDir,
            Path workDir
    ) {
        try {
            executePlannedStep(run, s, plan, sd, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, extensions, runDir, workDir);
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
            RunPlan plan,
            RunStepDef sd,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            Map<String, String> extPlanFileKeyByName,
            Path runDir,
            Path workDir,
            String ext
    ) throws Exception {

        String extFile = (ext == null) ? null : resolveExtensionPlanFileKey(extPlanFileKeyByName, ext);
        String extRepoPath = (ext == null) ? null : extRepoByName.get(ext);
        if (ext != null && (extRepoPath == null || extRepoPath.isBlank())) {
            // fallback to runner.extRepoPath (single repo for all extensions)
            extRepoPath = firstNonBlank(
                    runStepCommandService.planValue(plan.getSettings(), "extRepoPath", "ext-repo-path"),
                    runnerProperties.extRepoPath()
            );
        }
        if (ext != null && (extRepoPath == null || extRepoPath.isBlank())) {
            throw new IllegalStateException("Repo path not found for extension=" + ext + " (task.repoPath empty and runner.extRepoPath not set)");
        }

        // Важно: команды статичны и задаются в JSON. Здесь только подстановка токенов.
        if (sd.getRetry() != null && sd.getRetry().getCheckCommand() != null && !sd.getRetry().getCheckCommand().isEmpty()) {
            runRetryStep(run, s, plan, sd, needMain, mainRepoPath, extRepoPath, ext, extFile, runDir, workDir);
            return;
        }

        if (sd.getCommand() == null || sd.getCommand().isEmpty()) {
            throw new IllegalArgumentException("Step command is empty. code=" + sd.getCode());
        }

        Map<String, String> ctx = runStepCommandService.buildContext(
                run,
                s,
                plan.getSettings(),
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

        if (isSmokeTestsStep(code)) {
            smokeTestConfigService.markSmokeTestsStarted(plan);
            executeSmokeTestsStepAllowingReportGeneration(run, plan, code, title, command, workDir);
            return;
        }

        runStepExecutor.execute(run, code, title, command, workDir);
    }

    private void executeSmokeTestsStepAllowingReportGeneration(
            ExecutionRun run,
            RunPlan plan,
            String code,
            String title,
            List<String> command,
            Path workDir
    ) throws Exception {
        try {
            int exitCode = runStepExecutor.executeAllowFailure(run, code, title, command, workDir);
            if (exitCode == 0) {
                return;
            }

            markSmokeTestsFailedQuietly(run, plan, code);
            auditLogService.warn(
                    LogType.STEP_FAILED,
                    "Smoke testing finished with errors. Remaining smoke/report steps will continue; production update will be blocked.",
                    "{\"code\":" + j(code) + ",\"exitCode\":" + exitCode + "}",
                    null,
                    "system",
                    run.getId()
            );
        } catch (Exception e) {
            markSmokeTestsFailedQuietly(run, plan, code);
            auditLogService.warn(
                    LogType.STEP_FAILED,
                    "Smoke testing failed before completion. Remaining smoke/report steps will continue; production update will be blocked.",
                    "{\"code\":" + j(code) + ",\"error\":" + j(String.valueOf(e.getMessage())) + "}",
                    null,
                    "system",
                    run.getId()
            );
        }
    }

    private void markSmokeTestsFailedQuietly(ExecutionRun run, RunPlan plan, String code) {
        try {
            smokeTestConfigService.markSmokeTestsFailed(plan);
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.STEP_FAILED,
                    "Cannot write smoke testing FAILED status: " + e.getMessage(),
                    "{\"code\":" + j(code) + ",\"error\":" + j(String.valueOf(e.getMessage())) + "}",
                    null,
                    "system",
                    run.getId()
            );
        }
    }

    private void runRetryStep(
            ExecutionRun run,
            Settings s,
            RunPlan plan,
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
                    plan.getSettings(),
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

    private List<String> resolveExtensions(List<UpdateTask> extTasks) {
        TreeSet<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (extTasks != null) {
            extTasks.stream()
                    .map(UpdateTask::getExtensionName)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(values::add);
        }
        return new ArrayList<>(values);
    }

    private Map<String, String> resolveExtensionPlanFileKeys(List<UpdateTask> extTasks) {
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (extTasks == null) {
            return values;
        }

        for (UpdateTask task : extTasks) {
            if (task == null || !StringUtils.hasText(task.getExtensionName())) {
                continue;
            }

            String extensionName = task.getExtensionName().trim();
            String explicitKey = StringUtils.hasText(task.getExtensionPlanFileKey())
                    ? task.getExtensionPlanFileKey().trim()
                    : null;
            String fallbackKey = safeFileName(extensionName);
            String current = values.get(extensionName);

            if (!StringUtils.hasText(current)) {
                values.put(extensionName, firstNonBlank(explicitKey, fallbackKey));
                continue;
            }

            if (StringUtils.hasText(explicitKey) && current.equals(fallbackKey)) {
                values.put(extensionName, explicitKey);
            }
        }
        return values;
    }

    private String resolveExtensionPlanFileKey(Map<String, String> extPlanFileKeyByName, String ext) {
        if (!StringUtils.hasText(ext)) {
            return null;
        }
        String configured = extPlanFileKeyByName == null ? null : extPlanFileKeyByName.get(ext);
        return firstNonBlank(configured, safeFileName(ext));
    }

    private List<UpdateTask> loadTasksForStage(RunStage stage) {
        return switch (stage) {
            case TEST -> updateTaskRepository.findByStatusInOrderByCreatedAtAsc(List.of(TaskStatus.NEW, TaskStatus.TEST_FAILED));
            case PRODUCTION -> updateTaskRepository.findByStatusInOrderByCreatedAtAsc(List.of(TaskStatus.TEST_OK));
        };
    }

    private DependencyGraphState prepareDependencyGraphForTestRun(
            ExecutionRun run,
            Settings settings,
            DependencyGraphState initialState
    ) {
        DependencyGraphState state = initialState == null ? dependencyGraphStateService.getState() : initialState;
        if (state == null || !state.isGraphIsStale()) {
            return state;
        }

        if (!settings.isDependencyGraphRebuildEnabled()) {
            auditLogService.warn(
                    LogType.RUN_STARTED,
                    "Dependency graph is stale and rebuild before TEST is disabled. Test object list will be generated from stale graph snapshot.",
                    "{\"runId\":" + j(String.valueOf(run.getId()))
                            + ",\"dependencySnapshotId\":" + j(snapshotId(state))
                            + ",\"staleSince\":" + j(state.getStaleSince() == null ? "" : String.valueOf(state.getStaleSince()))
                            + ",\"staleReason\":" + j(state.getStaleReason() == null ? "" : state.getStaleReason()) + "}",
                    null,
                    "system",
                    run.getId()
            );
            registerDirtyObjectsFromStaleSnapshot(run, state, "rebuild-disabled");
            return state;
        }

        int waitMinutes = SettingsService.normalizeDependencyGraphRebuildWaitMinutes(
                settings.getDependencyGraphRebuildWaitMinutes(),
                60
        );
        auditLogService.info(
                LogType.RUN_STARTED,
                "Dependency graph is stale. Rebuild before TEST requested.",
                "{\"runId\":" + j(String.valueOf(run.getId()))
                        + ",\"waitMinutes\":" + waitMinutes
                        + ",\"dependencySnapshotId\":" + j(snapshotId(state))
                        + ",\"staleSince\":" + j(state.getStaleSince() == null ? "" : String.valueOf(state.getStaleSince()))
                        + ",\"staleReason\":" + j(state.getStaleReason() == null ? "" : state.getStaleReason()) + "}",
                null,
                "system",
                run.getId()
        );

        DependencyGraphRebuildCoordinator.WaitResult waitResult =
                dependencyGraphRebuildCoordinator.startIfStaleAndWait(
                        "TEST run before test object list generation",
                        run.getId(),
                        Duration.ofMinutes(waitMinutes)
                );

        DependencyGraphState refreshed = dependencyGraphStateService.getState();
        DependencySnapshot activeSnapshot = refreshed == null ? null : refreshed.getActiveSnapshot();
        run.setDependencySnapshot(activeSnapshot);
        executionRunRepository.save(run);

        if (waitResult.ready()) {
            auditLogService.info(
                    LogType.RUN_STARTED,
                    "Dependency graph rebuild completed before TEST. Test object list will use fresh graph snapshot.",
                    "{\"runId\":" + j(String.valueOf(run.getId()))
                            + ",\"dependencySnapshotId\":" + j(activeSnapshot == null ? "" : String.valueOf(activeSnapshot.getId()))
                            + ",\"started\":" + waitResult.started()
                            + ",\"alreadyRunning\":" + waitResult.alreadyRunning() + "}",
                    null,
                    "system",
                    run.getId()
            );
            return refreshed;
        }

        auditLogService.warn(
                LogType.RUN_STARTED,
                "Dependency graph rebuild did not finish before TEST wait period. Test object list will be generated from stale graph snapshot.",
                "{\"runId\":" + j(String.valueOf(run.getId()))
                        + ",\"status\":" + j(String.valueOf(waitResult.status()))
                        + ",\"waitMinutes\":" + waitMinutes
                        + ",\"dependencySnapshotId\":" + j(activeSnapshot == null ? "" : String.valueOf(activeSnapshot.getId()))
                        + ",\"started\":" + waitResult.started()
                        + ",\"alreadyRunning\":" + waitResult.alreadyRunning() + "}",
                null,
                "system",
                run.getId()
        );
        registerDirtyObjectsFromStaleSnapshot(run, refreshed, String.valueOf(waitResult.status()));
        return refreshed;
    }

    private void registerDirtyObjectsFromStaleSnapshot(ExecutionRun run, DependencyGraphState state, String reason) {
        DependencySnapshot activeSnapshot = state == null ? null : state.getActiveSnapshot();
        if (activeSnapshot == null) {
            auditLogService.warn(
                    LogType.RUN_STARTED,
                    "Cannot register affected test objects from stale graph because active dependency snapshot is missing.",
                    "{\"runId\":" + j(String.valueOf(run.getId())) + ",\"reason\":" + j(reason == null ? "" : reason) + "}",
                    null,
                    "system",
                    run.getId()
            );
            return;
        }

        List<DependencyGraphDirtyItem> dirtyItems = dependencyGraphStateService.pendingDirtyItems();
        if (dirtyItems.isEmpty()) {
            auditLogService.warn(
                    LogType.RUN_STARTED,
                    "Dependency graph is stale, but there are no pending dirty common modules. Test object list will be generated from current changed objects only.",
                    "{\"runId\":" + j(String.valueOf(run.getId()))
                            + ",\"dependencySnapshotId\":" + j(String.valueOf(activeSnapshot.getId()))
                            + ",\"reason\":" + j(reason == null ? "" : reason) + "}",
                    null,
                    "system",
                    run.getId()
            );
            return;
        }

        int affectedObjects = changedObjectService.registerObjectsFromDirtyModules(activeSnapshot, dirtyItems, null);
        auditLogService.warn(
                LogType.RUN_STARTED,
                "Affected test objects were registered from stale graph snapshot. Test object list will be generated from stale graph data.",
                "{\"runId\":" + j(String.valueOf(run.getId()))
                        + ",\"dependencySnapshotId\":" + j(String.valueOf(activeSnapshot.getId()))
                        + ",\"dirtyModules\":" + dirtyItems.size()
                        + ",\"affectedObjects\":" + affectedObjects
                        + ",\"reason\":" + j(reason == null ? "" : reason) + "}",
                null,
                "system",
                run.getId()
        );
    }

    private static String snapshotId(DependencyGraphState state) {
        DependencySnapshot snapshot = state == null ? null : state.getActiveSnapshot();
        return snapshot == null ? "" : String.valueOf(snapshot.getId());
    }

    private TaskStatus successStatus(RunStage stage) {
        return stage == RunStage.TEST ? TaskStatus.TEST_OK : TaskStatus.UPDATED;
    }

    private void afterStageSuccess(RunStage stage) {
        if (stage == RunStage.TEST) {
            changedObjectService.markTestingSucceeded();
        } else {
            changedObjectService.markProductionSucceeded();
        }
    }

    private void afterStageFailure(RunStage stage, List<UpdateTask> tasks) {
        if (stage != RunStage.TEST) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (UpdateTask task : tasks) {
            task.setStatus(TaskStatus.TEST_FAILED);
            task.setUpdatedAt(now);
        }
        if (!tasks.isEmpty()) {
            updateTaskRepository.saveAll(tasks);
        }
        changedObjectService.markTestingFailed();
    }

    private void verifyTestResult(ExecutionRun run, Settings settings, RunPlan plan) throws Exception {
        if (!smokeTestConfigService.isSmokeTestsEnabled(plan)) {
            return;
        }

        if (settings != null && settings.isIgnoreTestResults()) {
            forceSmokeTestsSuccess(run, plan, "TEST result verification");
            return;
        }

        smokeTestConfigService.acceptNonBlockingAllureFailuresIfApplicable(plan, run);
        SmokeTestConfigService.SmokeTestStatusInfo statusInfo = smokeTestConfigService.readStatusInfo(plan);
        if (statusInfo.status() == SmokeTestConfigService.SmokeTestStatus.SUCCESS) {
            return;
        }

        throw new IllegalStateException(
                statusInfo.status().description()
                        + " result=" + (statusInfo.rawValue() == null ? "" : statusInfo.rawValue())
                        + ", file=" + statusInfo.path()
        );
    }

    private void validateSmokeStatusBeforeProductionRun(ExecutionRun run, Settings settings, RunPlan plan) throws Exception {
        if (settings != null && settings.isIgnoreTestResults()) {
            forceSmokeTestsSuccess(run, plan, "PRODUCTION pre-check");
            return;
        }

        SmokeTestConfigService.SmokeTestStatusInfo statusInfo = smokeTestConfigService.readStatusInfo(plan);
        auditLogService.info(
                LogType.RUN_STARTED,
                "Статус тестирования перед обновлением рабочей базы: " + statusInfo.status().description(),
                "{\"status\":\"" + statusInfo.status().name()
                        + "\",\"rawValue\":" + j(statusInfo.rawValue())
                        + ",\"path\":" + j(String.valueOf(statusInfo.path())) + "}",
                null,
                "system",
                run.getId()
        );

        if (statusInfo.status().blocksProduction()) {
            throw new IllegalStateException(
                    "Обновление рабочей базы заблокировано. "
                            + statusInfo.status().description()
                            + " file=" + statusInfo.path()
            );
        }
    }

    private void forceSmokeTestsSuccess(ExecutionRun run, RunPlan plan, String reason) throws Exception {
        SmokeTestConfigService.SmokeTestStatusInfo statusInfo = smokeTestConfigService.markSmokeTestsSucceeded(plan);
        auditLogService.warn(
                LogType.RUN_STARTED,
                "Smoke testing result ignored by settings. Status file forced to SUCCESS.",
                "{\"reason\":" + j(reason)
                        + ",\"status\":\"" + statusInfo.status().name()
                        + "\",\"rawValue\":" + j(statusInfo.rawValue())
                        + ",\"path\":" + j(String.valueOf(statusInfo.path())) + "}",
                null,
                "system",
                run.getId()
        );
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

    private static boolean isExtensionPlansStep(RunStepDef sd) {
        return sd != null && "extensionplans".equals(norm(sd.getSpecial()));
    }

    private static boolean isSmokeTestsStep(String code) {
        String normalized = norm(code).replace("-", "_");
        return "smoke_tests".equals(normalized) || "smoketests".equals(normalized);
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
