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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final RunnerLogsCleanupService runnerLogsCleanupService;
    private final DependencyGraphStateService dependencyGraphStateService;
    private final DependencyGraphRebuildCoordinator dependencyGraphRebuildCoordinator;
    private final ChangedObjectService changedObjectService;
    private final SmokeTestConfigService smokeTestConfigService;
    private final TaskChangedFileService taskChangedFileService;
    private final DependencySnapshotCleanupService dependencySnapshotCleanupService;

    @Value("${app.dependency-graph.test-wait-minutes:60}")
    private long dependencyGraphTestWaitMinutes;

    private boolean tryAcquireRunLock() {
        Boolean ok = jdbcTemplate.queryForObject("select pg_try_advisory_lock(987654321)", Boolean.class);
        return Boolean.TRUE.equals(ok);
    }

    private void releaseRunLock() {
        jdbcTemplate.queryForObject("select pg_advisory_unlock(987654321)", Boolean.class);
    }

    public Optional<ExecutionRun> runScheduled(RunStage stage, OffsetDateTime plannedFor) {

        if (!tryAcquireRunLock()) return Optional.empty();

        try {

            Optional<ExecutionRun> existingRun = executionRunRepository.findTopByPlannedForAndStageOrderByStartedAtDesc(plannedFor, stage);
            if (existingRun.isPresent()) {
                ExecutionRun existing = existingRun.get();
                if (existing.getStatus() == RunStatus.SUCCESS) {
                    return existingRun;
                }
                if (existing.getStatus() == RunStatus.RUNNING) {
                    existing.setStatus(RunStatus.FAILED);
                    existing.setFinishedAt(OffsetDateTime.now());
                    existing.setErrorSummary("Previous run was marked as RUNNING, but no active advisory lock exists. Marked as failed before retry.");
                    executionRunRepository.save(existing);
                }
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

            List<UpdateTask> tasks = List.of();

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


            if (stage == RunStage.TEST && graphState.isGraphIsStale()) {
                Settings rebuildSettings = settingsRepository.findById(1L).orElseThrow();

                if (!rebuildSettings.isDependencyGraphRebuildEnabled()) {
                    if (activeSnapshot != null) {
                        changedObjectService.registerObjectsFromDirtyModules(
                                activeSnapshot,
                                dependencyGraphStateService.pendingDirtyItems(),
                                null
                        );
                        run.setDependencySnapshot(activeSnapshot);
                        executionRunRepository.save(run);
                    }

                    auditLogService.warn(
                            LogType.RUN_STARTED,
                            "Dependency graph rebuild is disabled by settings. Test object list will be built from stale graph snapshot.",
                            "{\"runId\":" + j(String.valueOf(run.getId()))
                                    + ",\"dependencySnapshotId\":" + j(activeSnapshot == null ? "" : String.valueOf(activeSnapshot.getId()))
                                    + ",\"staleSince\":" + j(graphState.getStaleSince() == null ? "" : String.valueOf(graphState.getStaleSince()))
                                    + ",\"staleReason\":" + j(graphState.getStaleReason() == null ? "" : graphState.getStaleReason()) + "}",
                            null,
                            "system",
                            run.getId()
                    );
                } else {
                    long waitMinutes = Math.max(0L, dependencyGraphTestWaitMinutes);

                    auditLogService.info(
                            LogType.RUN_STARTED,
                            "Dependency graph is stale. Rebuild will be started or awaited before TEST run.",
                            "{\"runId\":" + j(String.valueOf(run.getId()))
                                    + ",\"staleSince\":" + j(graphState.getStaleSince() == null ? "" : String.valueOf(graphState.getStaleSince()))
                                    + ",\"staleReason\":" + j(graphState.getStaleReason() == null ? "" : graphState.getStaleReason())
                                    + ",\"waitMinutes\":" + waitMinutes + "}",
                            null,
                            "system",
                            run.getId()
                    );

                    DependencyGraphRebuildCoordinator.WaitResult waitResult =
                            dependencyGraphRebuildCoordinator.startIfStaleAndWait(
                                    "TEST run " + run.getId(),
                                    run.getId(),
                                    Duration.ofMinutes(waitMinutes)
                            );

                    graphState = dependencyGraphStateService.getState();
                    activeSnapshot = graphState.getActiveSnapshot();

                    if (waitResult.ready()) {
                        run.setDependencySnapshot(activeSnapshot);
                        executionRunRepository.save(run);

                        auditLogService.info(
                                LogType.RUN_STARTED,
                                "Dependency graph rebuild completed before TEST run.",
                                "{\"runId\":" + j(String.valueOf(run.getId()))
                                        + ",\"dependencySnapshotId\":" + j(activeSnapshot == null ? "" : String.valueOf(activeSnapshot.getId()))
                                        + ",\"started\":" + waitResult.started()
                                        + ",\"alreadyRunning\":" + waitResult.alreadyRunning() + "}",
                                null,
                                "system",
                                run.getId()
                        );
                    } else if (waitResult.timedOut()) {
                        DependencySnapshot staleSnapshot =
                                waitResult.activeSnapshot() == null ? activeSnapshot : waitResult.activeSnapshot();
                        if (staleSnapshot != null) {
                            changedObjectService.registerObjectsFromDirtyModules(
                                    staleSnapshot,
                                    dependencyGraphStateService.pendingDirtyItems(),
                                    null
                            );
                            run.setDependencySnapshot(staleSnapshot);
                            executionRunRepository.save(run);
                        }

                        auditLogService.warn(
                                LogType.RUN_STARTED,
                                "Dependency graph rebuild wait timeout. Test object list will be built from stale graph snapshot.",
                                "{\"runId\":" + j(String.valueOf(run.getId()))
                                        + ",\"waitMinutes\":" + waitMinutes
                                        + ",\"dependencySnapshotId\":" + j(staleSnapshot == null ? "" : String.valueOf(staleSnapshot.getId()))
                                        + ",\"started\":" + waitResult.started()
                                        + ",\"alreadyRunning\":" + waitResult.alreadyRunning()
                                        + ",\"staleSince\":" + j(graphState.getStaleSince() == null ? "" : String.valueOf(graphState.getStaleSince()))
                                        + ",\"staleReason\":" + j(graphState.getStaleReason() == null ? "" : graphState.getStaleReason()) + "}",
                                null,
                                "system",
                                run.getId()
                        );
                    } else {
                        throw new IllegalStateException(
                                "Dependency graph rebuild failed before TEST run: " + waitResult.status()
                        );
                    }
                }
            }

            Settings s = settingsRepository.findById(1L).orElseThrow();
            RunPlan plan = stepPlanLoader.loadPlan(stage);

            tasks = loadTasksForStage(stage);
            if (tasks.isEmpty()) {
                run.setStatus(RunStatus.SUCCESS);
                run.setFinishedAt(OffsetDateTime.now());
                executionRunRepository.save(run);
                cleanupOldDependencySnapshotsAsync(run);
                auditLogService.info(LogType.RUN_FINISHED, "Nothing to do", "{\"stage\":" + j(stage.name()) + "}", null, "system", run.getId());
                return Optional.of(run);
            }

            if (stage == RunStage.TEST) {
                taskChangedFileService.registerDirectObjectsFromStoredChanges(tasks, run.getId());
            }

            boolean needMain = tasks.stream().anyMatch(t -> t.getTargetType() == TargetType.MAIN);
            List<UpdateTask> extTasks = tasks.stream().filter(t -> t.getTargetType() == TargetType.EXTENSION).toList();
            List<String> extensions = resolveExtensions(extTasks);
            Map<String, String> extPlanFileKeyByName = resolveExtensionPlanFileKeys(extTasks);

            Path logRoot = Path.of(runnerProperties.logDir());
            Path runDir = logRoot.resolve("run-" + run.getId());
            Path workDir = runDir;

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
                if (stage == RunStage.TEST) {
                    smokeTestConfigService.prepareOutputFile(plan, workDir);
                    String testResultFile = plan.getTestResultFile();
                    if (StringUtils.hasText(testResultFile)) {
                        plan.getSettings().put("testResultFile", testResultFile);
                        plan.getSettings().put("test-result-file", testResultFile);
                    }
                }

                executePlanSteps(run, s, plan, orderedSteps, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, extensions, runDir, workDir, true, executedAlwaysSteps);

                if (stage == RunStage.TEST) {
                    if (smokeTestConfigService.hasConfiguredOutputFile(plan)) {
                        smokeTestConfigService.ensureGeneratedForTesting(plan, run, workDir);
                    }
                    verifyTestResult(run, s, plan, needMain, mainRepoPath, extRepoByName, runDir, workDir);
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
                cleanupOldDependencySnapshotsAsync(run);

                auditLogService.info(LogType.RUN_FINISHED,
                        "Run finished successfully. tasks=" + tasks.size(),
                        "{\"stage\":" + j(stage.name()) + ",\"updatedTasks\":" + tasks.size() + ",\"needMain\":" + needMain + ",\"extensions\":" + extensions.size() + "}",
                        null, "system", run.getId());

                return Optional.of(run);
            } catch (Exception e) {
                if (stage == RunStage.TEST && smokeTestConfigService.hasConfiguredOutputFile(plan)) {
                    try {
                        smokeTestConfigService.ensureGeneratedForTesting(plan, run, workDir);
                    } catch (Exception generateError) {
                        auditLogService.warn(
                                LogType.STEP_FAILED,
                                "Smoke xUnit config generation failed: " + generateError.getMessage(),
                                "{\"runId\":" + j(String.valueOf(run.getId()))
                                        + ",\"error\":" + j(String.valueOf(generateError.getMessage())) + "}",
                                null,
                                "system",
                                run.getId()
                        );
                    }
                }

                afterStageFailure(stage, tasks);
                run.setStatus(RunStatus.FAILED);
                run.setFinishedAt(OffsetDateTime.now());
                run.setErrorSummary(trim(PasswordMasker.maskText(e.getMessage()), 3500));
                executionRunRepository.save(run);
                cleanupOldDependencySnapshotsAsync(run);

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
            releaseRunLock();
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
        if (extensions != null && !extensions.isEmpty()) {
            for (String ext : extensions) {
                String extFile = resolveExtensionPlanFileKey(extPlanFileKeyByName, ext);
                RunPlan extPlan = stepPlanLoader.loadExtensionPlan(basePlan, ext, extFile)
                        .orElseThrow(() -> new IllegalStateException("Extension plan pattern is not configured for extension=" + ext));
                executeSingleExtensionPlan(run, s, extPlan, ext, extFile, needMain, mainRepoPath, extRepoByName, runDir, workDir);
            }
            return;
        }

        List<StepPlanLoader.ExtensionPlanSpec> discoveredPlans = stepPlanLoader.loadDiscoveredExtensionPlans(basePlan);
        if (discoveredPlans.isEmpty()) {
            throw new IllegalStateException("Extension plans not found by pattern: " + basePlan.getExtensionPlanFilePattern());
        }
        for (StepPlanLoader.ExtensionPlanSpec discovered : discoveredPlans) {
            executeSingleExtensionPlan(
                    run,
                    s,
                    discovered.plan(),
                    discovered.extensionName(),
                    discovered.extFile(),
                    needMain,
                    mainRepoPath,
                    extRepoByName,
                    runDir,
                    workDir
            );
        }
    }

    private void executeSingleExtensionPlan(
            ExecutionRun run,
            Settings s,
            RunPlan extPlan,
            String ext,
            String extFile,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            Path runDir,
            Path workDir
    ) throws Exception {
        Map<String, String> singleExtFileKey = Map.of(ext, extFile);
        List<RunStepDef> orderedSteps = collectOrderedSteps(extPlan);
        List<RunStepDef> alwaysSteps = collectAlwaysSteps(extPlan);
        Set<RunStepDef> executedAlwaysSteps = new LinkedHashSet<>();

        try {
            executePlanSteps(run, s, extPlan, orderedSteps, needMain, mainRepoPath, extRepoByName, singleExtFileKey, List.of(ext), runDir, workDir, false, executedAlwaysSteps);
            verifyTestResult(run, s, extPlan, needMain, mainRepoPath, extRepoByName, runDir, workDir);
        } finally {
            for (RunStepDef sd : alwaysSteps) {
                if (!executedAlwaysSteps.contains(sd)) {
                    tryExecutePlannedStepIgnore(run, s, extPlan, sd, needMain, mainRepoPath, extRepoByName, singleExtFileKey, List.of(ext), runDir, workDir);
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
            executeExtensionPlans(run, s, plan, needMain, mainRepoPath, extRepoByName, extPlanFileKeyByName, extensions, runDir, workDir);
            if (sd.isAlways()) {
                executedAlwaysSteps.add(sd);
            }
            return;
        }

        if (isGenerateSmokeConfigStep(sd)) {
            if (run.getStage() == RunStage.TEST) {
                smokeTestConfigService.generateForTesting(plan, run, workDir);
            }
            if (sd.isAlways()) {
                executedAlwaysSteps.add(sd);
            }
            return;
        }

        if (run.getStage() == RunStage.TEST && shouldGenerateSmokeConfig(sd)) {
            smokeTestConfigService.generateForTesting(plan, run, workDir);
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
            extRepoPath = firstNonBlank(
                    runStepCommandService.planValue(plan.getSettings(), "extRepoPath", "ext-repo-path"),
                    runnerProperties.extRepoPath()
            );
        }
        if (ext != null && (extRepoPath == null || extRepoPath.isBlank())) {
            throw new IllegalStateException("Repo path not found for extension=" + ext + " (task.repoPath empty and runner.extRepoPath not set)");
        }

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

        runStepExecutor.execute(run, code, title, command, workDir);
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

    private void cleanupOldDependencySnapshotsAsync(ExecutionRun run) {
        try {
            DependencyGraphState state = dependencyGraphStateService.getState();
            DependencySnapshot activeSnapshot = state == null ? null : state.getActiveSnapshot();
            if (activeSnapshot != null && activeSnapshot.getId() != null) {
                dependencySnapshotCleanupService.cleanupOldSnapshotsAsync(activeSnapshot.getId());
            }
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.RUN_FAILED,
                    "Dependency snapshot cleanup scheduling failed: " + e.getMessage(),
                    "{\"runId\":" + j(run == null ? "" : String.valueOf(run.getId()))
                            + ",\"error\":" + j(String.valueOf(e.getMessage())) + "}",
                    null,
                    "system",
                    run == null ? null : run.getId()
            );
        }
    }

    private void verifyTestResult(
            ExecutionRun run,
            Settings settings,
            RunPlan plan,
            boolean needMain,
            String mainRepoPath,
            Map<String, String> extRepoByName,
            Path runDir,
            Path workDir
    ) throws Exception {
        if (!shouldVerifyTestResult(plan)) {
            return;
        }

        String configured = plan.getTestResultFile();
        if (!StringUtils.hasText(configured)) {
            return;
        }

        String fallbackExtRepo = extRepoByName.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
        Map<String, String> ctx = runStepCommandService.buildContext(
                run,
                settings,
                plan.getSettings(),
                needMain,
                mainRepoPath,
                fallbackExtRepo,
                null,
                null,
                null,
                runDir,
                workDir
        );
        String rendered = runStepCommandService.render(configured, ctx);
        if (!StringUtils.hasText(rendered)) {
            return;
        }

        Path resultFile = Path.of(rendered);
        if (!resultFile.isAbsolute()) {
            resultFile = workDir.resolve(rendered).normalize();
        }
        if (!Files.exists(resultFile)) {
            throw new IllegalStateException("Test result file not found: " + resultFile);
        }

        String value = Files.readString(resultFile, StandardCharsets.UTF_8).trim();
        if ("0".equals(value)) {
            return;
        }
        if ("1".equals(value)) {
            throw new IllegalStateException("Тестирование завершилось с ошибкой. result=" + value + ", file=" + resultFile);
        }
        throw new IllegalStateException("Некорректное значение файла результата тестирования: " + value + ", file=" + resultFile);
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

    private static boolean isGenerateSmokeConfigStep(RunStepDef sd) {
        return sd != null && "generatesmokeconfig".equals(norm(sd.getSpecial()));
    }

    private static boolean shouldGenerateSmokeConfig(RunStepDef sd) {
        if (sd == null) {
            return false;
        }
        String code = norm(sd.getCode());
        if ("smoke_tests".equals(code) || "smoketests".equals(code)) {
            return true;
        }
        return containsSmokeConfigToken(sd.getCommand())
                || (sd.getRetry() != null
                && (containsSmokeConfigToken(sd.getRetry().getCheckCommand())
                || containsSmokeConfigToken(sd.getRetry().getOnFailCommand())));
    }

    private static boolean shouldVerifyTestResult(RunPlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return false;
        }
        for (RunStepDef step : plan.getSteps()) {
            if (step == null || !step.isEnabled()) {
                continue;
            }
            String code = norm(step.getCode());
            if ("smoke_tests".equals(code) || "smoketests".equals(code)) {
                return true;
            }
            if (containsTestResultToken(step.getCommand())) {
                return true;
            }
            if (step.getRetry() != null
                    && (containsTestResultToken(step.getRetry().getCheckCommand())
                    || containsTestResultToken(step.getRetry().getOnFailCommand()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSmokeConfigToken(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        for (String arg : command) {
            if (arg != null && (arg.contains("{{xunitConfigFile}}") || arg.contains("{{smokeConfigFile}}"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTestResultToken(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        for (String arg : command) {
            if (arg != null && (arg.contains("{{testResultFile}}") || arg.contains("{{test-result-file}}"))) {
                return true;
            }
        }
        return false;
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
