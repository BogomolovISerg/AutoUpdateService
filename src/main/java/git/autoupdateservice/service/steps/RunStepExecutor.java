package git.autoupdateservice.service.steps;

import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.domain.ExecutionRun;
import git.autoupdateservice.domain.LogEvent;
import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.domain.StepLogBlob;
import git.autoupdateservice.domain.StepLogKind;
import git.autoupdateservice.repo.StepLogBlobRepository;
import git.autoupdateservice.service.AuditLogService;
import git.autoupdateservice.service.ProcessRunner;
import git.autoupdateservice.service.StepLogAnalyzer;
import git.autoupdateservice.util.CommandScriptWriter;
import git.autoupdateservice.util.LogFileUtil;
import git.autoupdateservice.util.PasswordMasker;
import git.autoupdateservice.util.Platform;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RunStepExecutor {

    private final AuditLogService auditLogService;
    private final StepLogBlobRepository stepLogBlobRepository;
    private final StepLogAnalyzer stepLogAnalyzer;
    private final ProcessRunner processRunner;
    private final RunnerProperties runnerProperties;

    public void execute(ExecutionRun run, String code, String title, List<String> command, Path workDir) throws Exception {
        int exitCode = executeInternal(run, code, title, command, workDir, false);
        if (exitCode != 0) {
            throw new IllegalStateException(title + " failed");
        }
    }

    public int executeAllowFailure(ExecutionRun run, String code, String title, List<String> command, Path workDir) throws Exception {
        return executeInternal(run, code, title, command, workDir, true);
    }

    private int executeInternal(
            ExecutionRun run,
            String code,
            String title,
            List<String> command,
            Path workDir,
            boolean allowNonZero
    ) throws Exception {
        auditLogService.info(
                LogType.STEP_STARTED,
                title,
                buildStepStartedData(code, command),
                null,
                "system",
                run.getId()
        );

        Path runDir = resolveRunDir(run);
        Path stdout = runDir.resolve(code + ".stdout.log");
        Path stderr = runDir.resolve(code + ".stderr.log");

        LogFileUtil.truncateQuietly(stdout);
        LogFileUtil.truncateQuietly(stderr);

        Path debug = findDebugLogFile(command);
        if (debug != null) {
            LogFileUtil.truncateQuietly(debug);
        }

        try {
            Path script = runDir.resolve(code + (Platform.isWindows() ? ".cmd" : ".sh"));
            CommandScriptWriter.write(command, workDir, script, runnerProperties.windowsCodePage());
        } catch (Exception ignored) {
        }

        ProcessRunner.Result result = processRunner.run(command, workDir, stdout, stderr, Duration.ofHours(6));
        return logStepCompletion(run, code, title, command, result, stdout, stderr, debug, allowNonZero);
    }

    private Path resolveRunDir(ExecutionRun run) {
        return Path.of(runnerProperties.logDir()).resolve("run-" + run.getId());
    }

    private int logStepCompletion(
            ExecutionRun run,
            String code,
            String title,
            List<String> command,
            ProcessRunner.Result result,
            Path stdout,
            Path stderr,
            Path debug,
            boolean allowNonZero
    ) {
        Charset charset = chooseCharset();

        String stdoutText = LogFileUtil.readAllLimited(stdout, charset, 1_200_000);
        String stderrText = LogFileUtil.readAllLimited(stderr, charset, 1_200_000);
        String debugText = debug == null ? null : LogFileUtil.readAllLimited(debug, charset, 2_000_000);

        StepLogAnalyzer.Analysis analysis = stepLogAnalyzer.analyze(code, stdoutText, stderrText, debugText);
        boolean logErrors = analysis != null && analysis.hasErrors();
        String summary = analysis == null ? null : analysis.summary();
        boolean ok = result.exitCode() == 0 && !logErrors;

        String message = ok
                ? composeMessage(title, summary)
                : composeErrorMessage(title, summary, result.exitCode(), logErrors);

        String dataJson = buildStepDataJson(code, title, command, result, debug);
        LogEvent event = writeAuditLog(run, message, dataJson, ok, allowNonZero);

        saveStepLogBlob(event.getId(), run.getId(), code, StepLogKind.STDOUT, stdoutText);
        saveStepLogBlob(event.getId(), run.getId(), code, StepLogKind.STDERR, stderrText);
        if (debugText != null && !debugText.isBlank()) {
            saveStepLogBlob(event.getId(), run.getId(), code, StepLogKind.DEBUG, debugText);
        }

        if (ok) {
            return 0;
        }
        return result.exitCode() != 0 ? result.exitCode() : 2;
    }

    private LogEvent writeAuditLog(
            ExecutionRun run,
            String message,
            String dataJson,
            boolean ok,
            boolean allowNonZero
    ) {
        if (ok) {
            return auditLogService.infoReturn(LogType.STEP_FINISHED, message, dataJson, null, "system", run.getId());
        }
        if (allowNonZero) {
            return auditLogService.warnReturn(LogType.STEP_FAILED, message, dataJson, null, "system", run.getId());
        }
        return auditLogService.errorReturn(LogType.STEP_FAILED, message, dataJson, null, "system", run.getId());
    }

    private void saveStepLogBlob(UUID eventId, UUID runId, String code, StepLogKind kind, String content) {
        try {
            String masked = content == null ? "" : PasswordMasker.maskText(content);
            StepLogBlob blob = new StepLogBlob();
            blob.setEventId(eventId);
            blob.setRunId(runId);
            blob.setStepCode(code);
            blob.setKind(kind);
            blob.setContent(masked);
            stepLogBlobRepository.save(blob);
        } catch (Exception ignored) {
        }
    }

    private String composeMessage(String title, String summary) {
        if (summary == null || summary.isBlank()) {
            return title;
        }
        return title + "\n" + summary.trim();
    }

    private String composeErrorMessage(String title, String summary, int exitCode, boolean logErrors) {
        String error = (summary == null || summary.isBlank()) ? "Ошибка (см. детали)" : summary.trim();
        if (exitCode != 0) {
            return title + "\n" + error + "\n(exitCode=" + exitCode + ")";
        }
        if (logErrors) {
            return title + "\n" + error + "\n(ошибка по логам)";
        }
        return title + "\n" + error;
    }

    private String buildStepDataJson(
            String code,
            String title,
            List<String> command,
            ProcessRunner.Result result,
            Path debug
    ) {
        String debugFile = debug == null ? null : debug.toString();
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        builder.append("\"code\":").append(quoteJson(code)).append(',');
        builder.append("\"title\":").append(quoteJson(title)).append(',');
        builder.append("\"exitCode\":").append(result.exitCode()).append(',');
        builder.append("\"durationMs\":").append(result.durationMs()).append(',');
        builder.append("\"command\":").append(quoteJson(String.join(" ", command)));
        if (debugFile != null) {
            builder.append(',').append("\"debugFile\":").append(quoteJson(debugFile));
        }
        builder.append('}');
        return builder.toString();
    }

    private String buildStepStartedData(String code, List<String> command) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        builder.append("\"code\":").append(quoteJson(code)).append(',');
        builder.append("\"command\":").append(quoteJsonArray(command));
        builder.append('}');
        return builder.toString();
    }

    private String quoteJsonArray(List<String> values) {
        if (values == null) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        boolean first = true;
        for (String value : values) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(quoteJson(value));
        }
        builder.append(']');
        return builder.toString();
    }

    private String quoteJson(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
        return "\"" + escaped + "\"";
    }

    private Charset chooseCharset() {
        try {
            if (Platform.isWindows()) {
                int codePage = 65001;
                try {
                    String value = runnerProperties.windowsCodePage();
                    if (value != null && !value.isBlank()) {
                        codePage = Integer.parseInt(value.trim());
                    }
                } catch (Exception ignored) {
                }
                return switch (codePage) {
                    case 1251 -> Charset.forName("windows-1251");
                    case 866 -> Charset.forName("IBM866");
                    default -> StandardCharsets.UTF_8;
                };
            }
        } catch (Exception ignored) {
        }
        return StandardCharsets.UTF_8;
    }

    private Path findDebugLogFile(List<String> command) {
        if (command == null) {
            return null;
        }
        for (int index = 0; index < command.size(); index++) {
            if ("--debuglogfile".equalsIgnoreCase(command.get(index)) && index + 1 < command.size()) {
                try {
                    return Path.of(command.get(index + 1));
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
