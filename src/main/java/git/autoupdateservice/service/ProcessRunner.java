package git.autoupdateservice.service;

import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.util.CommandScriptWriter;
import git.autoupdateservice.util.Platform;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class ProcessRunner {

    private final RunnerProperties runnerProperties;

    public record Result(int exitCode, long durationMs, Path stdoutFile, Path stderrFile) {}

    public Result run(List<String> command, Path workDir, Path stdoutFile, Path stderrFile, Duration timeout)
            throws IOException, InterruptedException {

        Files.createDirectories(workDir);
        Files.createDirectories(stdoutFile.getParent());
        Files.createDirectories(stderrFile.getParent());

        List<String> osCommand = buildOsCommand(command, workDir, stdoutFile);

        ProcessBuilder pb = new ProcessBuilder(osCommand);
        pb.directory(workDir.toFile());
        pb.redirectOutput(stdoutFile.toFile());
        pb.redirectError(stderrFile.toFile());

        Instant start = Instant.now();
        Process p = pb.start();

        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Command timeout: " + String.join(" ", command));
        }

        int code = p.exitValue();
        long ms = Duration.between(start, Instant.now()).toMillis();
        return new Result(code, ms, stdoutFile, stderrFile);
    }

    /**
     * На Windows исполняем именно .cmd-файл, а не одну длинную строку через cmd /c "...".
     * Это убирает расхождение между тем, что сохранено в cmd, и тем, что реально запускается.
     */
    private List<String> buildOsCommand(List<String> command, Path workDir, Path stdoutFile) throws IOException {
        if (!Platform.isWindows()) {
            return command;
        }

        String comSpec = System.getenv("ComSpec");
        String cmdExe = (comSpec == null || comSpec.isBlank()) ? "cmd.exe" : comSpec;

        Path scriptFile = buildScriptPath(workDir, stdoutFile);
        CommandScriptWriter.write(command, workDir, scriptFile, runnerProperties.windowsCodePage());

        return List.of(
                cmdExe,
                "/d",
                "/v:off",
                "/c",
                scriptFile.toAbsolutePath().toString()
        );
    }

    private Path buildScriptPath(Path workDir, Path stdoutFile) {
        String name = stdoutFile.getFileName().toString();

        if (name.endsWith(".stdout.log")) {
            name = name.substring(0, name.length() - ".stdout.log".length());
        } else if (name.endsWith(".log")) {
            name = name.substring(0, name.length() - ".log".length());
        }

        return workDir.resolve(name + ".cmd");
    }
}