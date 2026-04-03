package git.autoupdateservice.service;

import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.util.CommandFormatter;
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

        List<String> osCommand = wrapForOs(command);

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
     * На Windows запускаем через cmd.exe /c, предварительно выставляя кодовую страницу (chcp).
     * Это помогает, когда в параметрах встречается кириллица, а исполняемый файл опирается на консольную кодировку.
     */
    private List<String> wrapForOs(List<String> command) {
        if (!Platform.isWindows()) return command;

        String comSpec = System.getenv("ComSpec");
        String cmdExe = (comSpec == null || comSpec.isBlank()) ? "cmd.exe" : comSpec;

        String cmdLine = CommandFormatter.toCmdExeSingleLine(command, runnerProperties.windowsCodePage());
        return List.of(cmdExe, "/c", cmdLine);
    }
}

