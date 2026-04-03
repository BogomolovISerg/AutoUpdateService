package git.autoupdateservice.service;

import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.ExecutionRunRepository;
import git.autoupdateservice.repo.SettingsRepository;
import git.autoupdateservice.repo.StepLogBlobRepository;
import git.autoupdateservice.repo.UpdateTaskRepository;
import git.autoupdateservice.service.steps.RunStepDef;
import git.autoupdateservice.service.steps.StepPlanLoader;
import git.autoupdateservice.util.CommandScriptWriter;
import git.autoupdateservice.util.LogFileUtil;
import git.autoupdateservice.util.Platform;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class UpdateExecutor {

    private final SettingsRepository settingsRepository;
    private final UpdateTaskRepository updateTaskRepository;
    private final ExecutionRunRepository executionRunRepository;
    private final AuditLogService auditLogService;

    private final StepLogBlobRepository stepLogBlobRepository;

    private final StepLogAnalyzer stepLogAnalyzer;

    private final StepPlanLoader stepPlanLoader;
    private final ProcessRunner processRunner;
    private final RunnerProperties runnerProperties;

    private final JdbcTemplate jdbcTemplate;
    private final RunnerLogsCleanupService runnerLogsCleanupService;

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

            ExecutionRun run = new ExecutionRun();
            run.setPlannedFor(plannedFor);
            run.setStartedAt(OffsetDateTime.now());
            run.setStatus(RunStatus.RUNNING);
            run = executionRunRepository.save(run);

            auditLogService.info(
                    LogType.RUN_STARTED,
                    "Run started for planned_for=" + plannedFor,
                    "{\"runId\":" + j(String.valueOf(run.getId())) + ",\"plannedFor\":" + j(String.valueOf(plannedFor)) + "}",
                    null,
                    "system",
                    run.getId()
            );

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

            List<UpdateTask> tasks = updateTaskRepository.findReadyToRun(TaskStatus.NEW, LocalDate.now());
            if (tasks.isEmpty()) {
                run.setStatus(RunStatus.SUCCESS);
                run.setFinishedAt(OffsetDateTime.now());
                executionRunRepository.save(run);
                auditLogService.info(LogType.RUN_FINISHED, "Nothing to do", "{}", null, "system", run.getId());
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
                for (RunStepDef sd : normalSteps) {
                    executePlannedStep(run, s, sd, needMain, mainRepoPath, extRepoByName, extensions, runDir, workDir);
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
                run.setErrorSummary(trim(e.getMessage(), 3500));
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

        Map<String, String> ctx = buildCtx(run, s, needMain, mainRepoPath, extRepoPath, ext, extFile, null, runDir, workDir);

        String code = firstNonBlank(render(sd.getCode(), ctx), "STEP_" + sd.getOrder());
        String title = firstNonBlank(render(sd.getTitle(), ctx), code);

        List<String> command = expandCommand(sd.getCommand(), ctx, true);

        step(run, code, title, command, workDir);
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
            Map<String, String> ctx = buildCtx(run, s, needMain, mainRepoPath, extRepoPath, ext, extFile, attempt, runDir, workDir);

            String baseCode = firstNonBlank(render(sd.getCode(), ctx), "RETRY_" + sd.getOrder());
            String baseTitle = firstNonBlank(render(sd.getTitle(), ctx), baseCode);

            String checkCode = baseCode + "_TRY" + attempt;
            String checkTitle = baseTitle + " (попытка " + attempt + "/" + maxAttempts + ")";

            List<String> checkCmd = expandCommand(r.getCheckCommand(), ctx, true);

            int exit = runOnceNoThrow(run, checkCode, checkTitle, checkCmd, workDir);
            if (exit == 0) return;

            // onFail: например session kill (не валим весь запуск, просто логируем; дальше будет sleep и новая попытка)
            if (r.getOnFailCommand() != null && !r.getOnFailCommand().isEmpty()) {
                String failCode = baseCode + "_ONFAIL" + attempt;
                String failTitle = baseTitle + " (onFail, попытка " + attempt + ")";
                List<String> failCmd = expandCommand(r.getOnFailCommand(), ctx, true);
                runOnceNoThrow(run, failCode, failTitle, failCmd, workDir);
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

    /**
     * Выполнение команды: пишет stdout/stderr как обычно, но НЕ бросает исключение на exitCode!=0.
     * Возвращает exitCode.
     */
    private int runOnceNoThrow(ExecutionRun run, String code, String title, List<String> command, Path workDir) throws Exception {
        auditLogService.info(LogType.STEP_STARTED, title,
                buildStepStartedData(code, command),
                null, "system", run.getId());

        Path runDir = Path.of(runnerProperties.logDir()).resolve("run-" + run.getId());
        Path stdout = runDir.resolve(code + ".stdout.log");
        Path stderr = runDir.resolve(code + ".stderr.log");

        LogFileUtil.truncateQuietly(stdout);
        LogFileUtil.truncateQuietly(stderr);
        Path debug = findDebugLogFile(command);
        if (debug != null) LogFileUtil.truncateQuietly(debug);

        try {
            Path script = runDir.resolve(code + (Platform.isWindows() ? ".cmd" : ".sh"));
            CommandScriptWriter.write(command, workDir, script, runnerProperties.windowsCodePage());
        } catch (Exception ignored) {}

        ProcessRunner.Result r = processRunner.run(command, workDir, stdout, stderr, Duration.ofHours(6));

        return logStepCompletion(run, code, title, command, r, stdout, stderr, debug, true);
    }

    private void step(ExecutionRun run, String code, String title, List<String> command, Path workDir) throws Exception {
        auditLogService.info(LogType.STEP_STARTED, title,
                buildStepStartedData(code, command),
                null, "system", run.getId());

        Path runDir = Path.of(runnerProperties.logDir()).resolve("run-" + run.getId());
        Path stdout = runDir.resolve(code + ".stdout.log");
        Path stderr = runDir.resolve(code + ".stderr.log");

        // Каждый шаг пишет в отдельные файлы. На всякий случай чистим прошлые остатки.
        LogFileUtil.truncateQuietly(stdout);
        LogFileUtil.truncateQuietly(stderr);

        Path debug = findDebugLogFile(command);
        if (debug != null) {
            LogFileUtil.truncateQuietly(debug);
        }

        // Write a reproducible script next to logs (handy for debugging).
        try {
            Path script = runDir.resolve(code + (Platform.isWindows() ? ".cmd" : ".sh"));
            CommandScriptWriter.write(command, workDir, script, runnerProperties.windowsCodePage());
        } catch (Exception ignored) {}

        ProcessRunner.Result r = processRunner.run(command, workDir, stdout, stderr, Duration.ofHours(6));

        int exit = logStepCompletion(run, code, title, command, r, stdout, stderr, debug, false);
        if (exit != 0) {
            throw new IllegalStateException(title + " failed");
        }
    }

    /**
     * Единая точка логирования завершения шага:
     *  - сохраняем полный текст stdout/stderr/debug в БД (таблица step_log_blob)
     *  - анализируем логи (в т.ч. "скрытые" ошибки при exitCode==0)
     *  - в web-логе (log_event.message) пишем только "результат" (хвост/итог), а не весь лог.
     *
     * @return 0 если шаг успешен, иначе ненулевой код.
     *         Для retry-шага (allowNonZero=true) возвращаем exitCode либо 2 если "ошибка по логам".
     */
    private int logStepCompletion(
            ExecutionRun run,
            String code,
            String title,
            List<String> command,
            ProcessRunner.Result r,
            Path stdout,
            Path stderr,
            Path debug,
            boolean allowNonZero
    ) {
        java.nio.charset.Charset cs = chooseCharset();

        String stdoutText = LogFileUtil.readAllLimited(stdout, cs, 1_200_000);
        String stderrText = LogFileUtil.readAllLimited(stderr, cs, 1_200_000);
        String debugText = (debug == null) ? null : LogFileUtil.readAllLimited(debug, cs, 2_000_000);

        StepLogAnalyzer.Analysis a = stepLogAnalyzer.analyze(code, stdoutText, stderrText, debugText);
        boolean logErrors = a != null && a.hasErrors();
        String summary = (a == null) ? null : a.summary();

        boolean ok = (r.exitCode() == 0) && !logErrors;

        String msg;
        if (ok) {
            msg = composeMessage(title, summary);
        } else {
            // По ТЗ в web-интерфейсе выводим только текст ошибки (без модуля/строки),
            // но оставляем контекст шага (title).
            msg = composeErrorMessage(title, summary, r.exitCode(), logErrors);
        }

        String dataJson = buildStepDataJson(code, title, command, r, debug);

        LogEvent event;
        if (ok) {
            event = auditLogService.infoReturn(LogType.STEP_FINISHED, msg, dataJson, null, "system", run.getId());
        } else {
            // Для retry-ветки пишем WARN, для обычной — ERROR.
            if (allowNonZero) {
                event = auditLogService.warnReturn(LogType.STEP_FAILED, msg, dataJson, null, "system", run.getId());
            } else {
                event = auditLogService.errorReturn(LogType.STEP_FAILED, msg, dataJson, null, "system", run.getId());
            }
        }

        // Сохраняем полный текст файлов в БД
        saveStepLogBlob(event.getId(), run.getId(), code, StepLogKind.STDOUT, stdoutText);
        saveStepLogBlob(event.getId(), run.getId(), code, StepLogKind.STDERR, stderrText);
        if (debugText != null && !debugText.isBlank()) {
            saveStepLogBlob(event.getId(), run.getId(), code, StepLogKind.DEBUG, debugText);
        }

        if (ok) return 0;

        // В режиме retry важно различать "exitCode!=0" и "ошибка по логам".
        if (allowNonZero) {
            return (r.exitCode() != 0) ? r.exitCode() : 2;
        }

        return (r.exitCode() != 0) ? r.exitCode() : 2;
    }

    private void saveStepLogBlob(UUID eventId, UUID runId, String code, StepLogKind kind, String content) {
        try {
            if (content == null) content = "";
            StepLogBlob b = new StepLogBlob();
            b.setEventId(eventId);
            b.setRunId(runId);
            b.setStepCode(code);
            b.setKind(kind);
            b.setContent(content);
            stepLogBlobRepository.save(b);
        } catch (Exception ignored) {
            // не валим выполнение из-за проблем с логированием
        }
    }

    private String composeMessage(String title, String summary) {
        if (summary == null || summary.isBlank()) return title;
        return title + "\n" + summary.trim();
    }

    private String composeErrorMessage(String title, String summary, int exitCode, boolean logErrors) {
        String err = (summary == null || summary.isBlank()) ? "Ошибка (см. детали)" : summary.trim();
        // В сообщение не тащим огромные детали — только суть.
        if (exitCode != 0) {
            return title + "\n" + err + "\n(exitCode=" + exitCode + ")";
        }
        if (logErrors) {
            return title + "\n" + err + "\n(ошибка по логам)";
        }
        return title + "\n" + err;
    }

    private String buildStepDataJson(String code, String title, List<String> command, ProcessRunner.Result r, Path debug) {
        String cmd = String.join(" ", command);
        String debugFile = (debug == null) ? null : debug.toString();
        // Простое JSON без внешних зависимостей.
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"code\":").append(j(code)).append(',');
        sb.append("\"title\":").append(j(title)).append(',');
        sb.append("\"exitCode\":").append(r.exitCode()).append(',');
        sb.append("\"durationMs\":").append(r.durationMs()).append(',');
        sb.append("\"command\":").append(j(cmd));
        if (debugFile != null) {
            sb.append(',').append("\"debugFile\":").append(j(debugFile));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String j(String s) {
        if (s == null) return "\"\"";
        String x = s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
        return "\"" + x + "\"";
    }

    private String buildStepStartedData(String code, List<String> command) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"code\":").append(j(code)).append(',');
        sb.append("\"command\":").append(jarr(command));
        sb.append('}');
        return sb.toString();
    }

    private static String jarr(List<String> xs) {
        if (xs == null) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (String x : xs) {
            if (!first) sb.append(',');
            first = false;
            sb.append(j(x));
        }
        sb.append(']');
        return sb.toString();
    }

    private java.nio.charset.Charset chooseCharset() {
        // Для Windows стараемся читать в той кодировке, которую выставляли через chcp.
        try {
            if (Platform.isWindows()) {
                int cp = 65001;
                try {
                    String v = runnerProperties.windowsCodePage();
                    if (v != null && !v.isBlank()) cp = Integer.parseInt(v.trim());
                } catch (Exception ignored) {}
                return switch (cp) {
                    case 1251 -> java.nio.charset.Charset.forName("windows-1251");
                    case 866 -> java.nio.charset.Charset.forName("IBM866");
                    default -> java.nio.charset.StandardCharsets.UTF_8;
                };
            }
        } catch (Exception ignored) {}
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    private Path findDebugLogFile(List<String> command) {
        if (command == null) return null;
        for (int i = 0; i < command.size(); i++) {
            if ("--debuglogfile".equalsIgnoreCase(command.get(i)) && i + 1 < command.size()) {
                try {
                    return Path.of(command.get(i + 1));
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    // toJsonSafe больше не используем: данные логов делаем читабельными, многострочными.

    private Map<String, String> buildCtx(
            ExecutionRun run,
            Settings s,
            boolean needMain,
            String mainRepoPath,
            String extRepoPath,
            String ext,
            String extFile,
            Integer attempt,
            Path runDir,
            Path workDir
    ) {
        Map<String, String> m = new HashMap<>();

        // RunnerProperties (camelCase)
        m.put("binary", nvl(runnerProperties.binary()));
        m.put("ras", nvl(runnerProperties.rasAddress()));
        m.put("rac", nvl(runnerProperties.racPath()));
        m.put("db", nvl(runnerProperties.baseName()));
        m.put("dbUser", nvl(runnerProperties.dbUser()));
        m.put("dbPwd", nvl(runnerProperties.dbPassword()));
        m.put("ibConnection", nvl(runnerProperties.ibConnection()));
        m.put("repoUser", nvl(runnerProperties.repoUser()));
        m.put("repoPwd", nvl(runnerProperties.repoPassword()));

        // Алиасы в формате application.properties (kebab-case)
        m.put("ras-address", nvl(runnerProperties.rasAddress()));
        m.put("rac-path", nvl(runnerProperties.racPath()));
        m.put("base-name", nvl(runnerProperties.baseName()));
        m.put("db-user", nvl(runnerProperties.dbUser()));
        m.put("db-password", nvl(runnerProperties.dbPassword()));
        m.put("ib-connection", nvl(runnerProperties.ibConnection()));
        m.put("repo-user", nvl(runnerProperties.repoUser()));
        m.put("repo-password", nvl(runnerProperties.repoPassword()));

        // Prefer runner.* overrides if provided; otherwise fall back to DB settings
        String uccode = unquote(firstNonBlank(runnerProperties.uccode(), s.getUccode()));
        String lockMessage = unquote(firstNonBlank(runnerProperties.lockMessage(), s.getLockMessage()));
        m.put("uccode", nvl(uccode));
        m.put("lockMessage", nvl(lockMessage));

        // алиасы для совместимости с JSON, где используются kebab-case ключи
        m.put("lockMessage", nvl(lockMessage));
        m.put("uccode", nvl(uccode));

        // Optional alternative connection string for repo operations
        String ibConnectionRepo = firstNonBlank(runnerProperties.ibConnectionrepo(), runnerProperties.ibConnection());
        m.put("ibConnectionRepo", nvl(ibConnectionRepo));
        m.put("ibConnectionrepo", nvl(ibConnectionRepo));
        m.put("ib-connectionrepo", nvl(ibConnectionRepo));
        m.put("ib-connection-repo", nvl(ibConnectionRepo));

        m.put("needMain", String.valueOf(needMain));
        m.put("mainRepoPath", nvl(mainRepoPath));
        m.put("extRepoPath", nvl(extRepoPath));

        // алиасы для JSON-файлов, где используются kebab-case имена
        m.put("mainRepoPath", nvl(mainRepoPath));
        m.put("extRepoPath", nvl(extRepoPath));

        m.put("ext", nvl(ext));
        m.put("extFile", nvl(extFile));
        m.put("attempt", attempt == null ? "" : String.valueOf(attempt));

        m.put("runId", String.valueOf(run.getId()));
        // Важно: используем абсолютные пути, иначе после cd в workDir относительные значения
        // начинают "вкладываться" (например .\runner-logs\run-...\runner-logs\run-...)
        m.put("runDir", runDir.toAbsolutePath().toString());
        m.put("workDir", workDir.toAbsolutePath().toString());

        // В пользовательском runner-steps.json debuglogfile строится через {{log-dir}}.
        // Чтобы не смешивать логи разных запусков, подставляем сюда папку конкретного runId.
        m.put("log-dir", runDir.toAbsolutePath().toString());
        m.put("logDir", runDir.toAbsolutePath().toString());

        // На всякий случай — корневой каталог логов из настроек (может пригодиться в будущем).
        m.put("log-dir-root", nvl(runnerProperties.logDir()));
        m.put("logDirRoot", nvl(runnerProperties.logDir()));
        return m;
    }

    /**
     * Подстановка токенов {{...}} и нормализация массива аргументов.
     *
     * В пользовательском runner-steps.json элементы command могут быть записаны как:
     *  - "session lock" (две части в одной строке)
     *  - "--ras {{ras-address}}" (флаг и значение в одной строке, значение может содержать пробелы)
     *  - "updateext {{extRepoPath}}" (команда и параметр в одной строке)
     *
     * Поэтому каждую строку после подстановки разбиваем максимум на 2 аргумента:
     *  <head> <tail...>
     */
    private static List<String> expandCommand(List<String> src, Map<String, String> ctx, boolean strict) {
        if (src == null) return List.of();
        List<String> out = new ArrayList<>(src.size() * 2);
        for (String raw : src) {
            if (raw == null) continue;
            String rendered = renderTpl(raw, ctx, strict).trim();
            if (rendered.isBlank()) continue;

            int ws = firstWhitespace(rendered);
            if (ws < 0) {
                out.add(rendered);
            } else {
                String head = rendered.substring(0, ws).trim();
                String tail = rendered.substring(ws).trim();
                if (!head.isBlank()) out.add(head);
                if (!tail.isBlank()) out.add(tail);
            }
        }

        // sanity: не допускаем неразрешённых токенов в командах
        for (String a : out) {
            if (a != null && a.contains("{{")) {
                throw new IllegalStateException("Unresolved token in command arg: " + a);
            }
        }

        // Совместимость: иногда встречаются варианты флагов с дефисами.
        // В runner ожидаемые ключи: --ibconnection, --ibconnectionrepo.
        for (int i = 0; i < out.size(); i++) {
            String a = out.get(i);
            if (a == null) continue;
            String x = a.trim();
            if ("--ib-connection".equalsIgnoreCase(x)) out.set(i, "--ibconnection");
            if ("--ib-connectionrepo".equalsIgnoreCase(x) || "--ib-connection-repo".equalsIgnoreCase(x)) {
                out.set(i, "--ibconnectionrepo");
            }
        }
        return out;
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static final java.util.regex.Pattern VAR = java.util.regex.Pattern.compile("\\{\\{([\\w.-]+)}}");

    private static String renderTpl(String tpl, Map<String, String> ctx, boolean strict) {
        if (tpl == null) return null;
        var m = VAR.matcher(tpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String val = ctx.get(key);
            if (val == null) {
                if (strict) {
                    throw new IllegalStateException("Unknown token {{" + key + "}} in steps plan");
                }
                val = m.group(0);
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String render(String tpl, Map<String, String> ctx) {
        // нестрогий режим для title/code
        return renderTpl(tpl, ctx, false);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static String safeFileName(String s) {
        if (s == null) return "null";
        String x = s.trim();
        x = x.replaceAll("[^0-9A-Za-zА-Яа-я._-]+", "_");
        if (x.length() > 60) x = x.substring(0, 60);
        if (x.isBlank()) return "ext";
        return x;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    /** Убирает внешние двойные кавычки: "Text" -> Text. */
    private static String unquote(String s) {
        if (s == null) return null;
        String x = s.trim();
        if (x.length() >= 2 && x.startsWith("\"") && x.endsWith("\"")) {
            return x.substring(1, x.length() - 1);
        }
        return x;
    }
}
