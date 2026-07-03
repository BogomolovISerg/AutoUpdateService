package git.autoupdateservice.service;

import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.util.CommandScriptWriter;
import git.autoupdateservice.util.Platform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
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

        List<String> normalizedCommand = normalizeCommand(command);
        List<String> osCommand = buildOsCommand(normalizedCommand, workDir, stdoutFile);

        ProcessBuilder pb = new ProcessBuilder(osCommand);
        pb.directory(workDir.toFile());
        pb.redirectOutput(stdoutFile.toFile());
        pb.redirectError(stderrFile.toFile());
        configureLinuxGuiEnvironment(pb.environment(), workDir);

        Instant start = Instant.now();
        Process p = pb.start();

        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Command timeout: " + String.join(" ", normalizedCommand));
        }

        int code = p.exitValue();
        long ms = Duration.between(start, Instant.now()).toMillis();
        return new Result(code, ms, stdoutFile, stderrFile);
    }

    private List<String> normalizeCommand(List<String> command) {
        if (command == null || command.isEmpty() || Platform.isWindows()) {
            return command;
        }

        List<String> normalized = new ArrayList<>(command);
        String executable = normalized.get(0);
        String clientExecutable = clientBinaryForServerBinary(executable);
        if (!same(executable, clientExecutable)) {
            normalized.set(0, clientExecutable);
            log.info("В runner-команде указан серверный бинарник 1cv8s. Для GUI-запуска будет использован клиентский бинарник: {}", clientExecutable);
        }

        if (isOneCClientExecutable(normalized.get(0))) {
            normalized = compactOneCArguments(normalized);
        }
        return normalized;
    }

    private List<String> compactOneCArguments(List<String> command) {
        List<String> result = new ArrayList<>(command.size());
        for (int index = 0; index < command.size(); index++) {
            String current = command.get(index);
            String compacted = compactOneCArgument(current);
            if (!same(current, compacted)) {
                result.add(compacted);
                continue;
            }

            String option = oneCCompactOption(current);
            if (option != null && index + 1 < command.size()) {
                String value = command.get(++index);
                result.add(option + nvl(value).trim());
                continue;
            }

            result.add(current);
        }
        return result;
    }

    private String compactOneCArgument(String value) {
        if (value == null) {
            return null;
        }
        String option = oneCCompactOption(value);
        if (option == null) {
            return value;
        }
        String tail = value.trim().substring(option.length()).trim();
        return tail.isEmpty() ? value : option + tail;
    }

    private String oneCCompactOption(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String option : List.of("/s", "/f", "/out")) {
            if (lower.equals(option) || lower.startsWith(option + " ")) {
                return trimmed.substring(0, option.length());
            }
        }
        return null;
    }

    private boolean isOneCClientExecutable(String executable) {
        return "1cv8".equals(fileName(executable));
    }

    private String clientBinaryForServerBinary(String executable) {
        if (!"1cv8s".equals(fileName(executable))) {
            return executable;
        }
        int slash = lastSeparator(executable);
        if (slash < 0) {
            return "1cv8";
        }
        return executable.substring(0, slash + 1) + "1cv8";
    }

    private String fileName(String executable) {
        if (executable == null || executable.isBlank()) {
            return "";
        }
        int slash = lastSeparator(executable);
        return slash < 0 ? executable : executable.substring(slash + 1);
    }

    private int lastSeparator(String value) {
        if (value == null) {
            return -1;
        }
        return Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
    }

    private void configureLinuxGuiEnvironment(Map<String, String> environment, Path workDir) throws IOException {
        if (Platform.isWindows() || !linuxGuiEnvironmentEnabled()) {
            return;
        }

        Path envRoot = workDir.resolve("env").toAbsolutePath().normalize();
        Path home = envRoot.resolve("home");
        Path cache = envRoot.resolve("cache");
        Path config = envRoot.resolve("config");
        Path data = envRoot.resolve("data");
        Path runtime = envRoot.resolve("runtime");
        Path tmp = envRoot.resolve("tmp");

        Files.createDirectories(home);
        Files.createDirectories(cache.resolve("fontconfig"));
        Files.createDirectories(config);
        Files.createDirectories(data);
        Files.createDirectories(runtime);
        Files.createDirectories(tmp);
        setOwnerOnlyPermissions(home);
        setOwnerOnlyPermissions(runtime);

        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        environment.put("XDG_CACHE_HOME", cache.toString());
        environment.put("XDG_CONFIG_HOME", config.toString());
        environment.put("XDG_DATA_HOME", data.toString());
        environment.put("XDG_RUNTIME_DIR", runtime.toString());
        environment.put("TMPDIR", tmp.toString());
        environment.put("NO_AT_BRIDGE", "1");

        putIfNotBlank(environment, "DISPLAY", firstNonBlank(runnerProperties.display(), environment.get("DISPLAY")));
        putIfNotBlank(environment, "XAUTHORITY", firstNonBlank(runnerProperties.xauthority(), environment.get("XAUTHORITY")));

        log.info("Для runner-процесса подготовлено Linux GUI/XDG окружение: envRoot={}, DISPLAY={}",
                envRoot, environment.getOrDefault("DISPLAY", ""));
    }

    private boolean linuxGuiEnvironmentEnabled() {
        Boolean enabled = runnerProperties.linuxGuiEnvironmentEnabled();
        return enabled == null || enabled;
    }

    private void setOwnerOnlyPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
        }
    }

    private void putIfNotBlank(Map<String, String> environment, String key, String value) {
        if (value != null && !value.isBlank()) {
            environment.put(key, value.trim());
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private boolean same(String left, String right) {
        return nvl(left).equals(nvl(right));
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

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
