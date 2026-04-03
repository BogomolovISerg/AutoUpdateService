package git.autoupdateservice.service;

import git.autoupdateservice.config.RunnerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class RunnerLogsCleanupService {

    private final RunnerProperties runnerProperties;

    public void cleanupOldRuns() {
        int keepDays = runnerProperties.keepDays();
        if (keepDays <= 0) {
            log.info("Runner logs cleanup disabled, keepDays={}", keepDays);
            return;
        }

        Path root = Path.of(runnerProperties.logDir()).toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return;
        }

        Instant cutoff = Instant.now().minus(keepDays, ChronoUnit.DAYS);

        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("run-"))
                    .forEach(dir -> tryDeleteIfOld(dir, cutoff));
        } catch (Exception e) {
            log.warn("Failed to scan runner logs directory {}: {}", root, e.getMessage(), e);
        }
    }

    private void tryDeleteIfOld(Path dir, Instant cutoff) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(dir);
            if (lastModified.toInstant().isBefore(cutoff)) {
                deleteRecursively(dir);
                log.info("Deleted old runner log directory: {}", dir);
            }
        } catch (Exception e) {
            log.warn("Failed to delete old runner log directory {}: {}", dir, e.getMessage(), e);
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException("Cannot delete " + path, e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }
}