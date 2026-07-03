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

    public CleanupResult cleanupOldRuns(int keepDays) {
        if (keepDays <= 0) {
            log.info("Runner logs cleanup disabled, keepDays={}", keepDays);
            return new CleanupResult(0);
        }

        Path root = Path.of(runnerProperties.logDir()).toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return new CleanupResult(0);
        }

        Instant cutoff = Instant.now().minus(keepDays, ChronoUnit.DAYS);
        int[] deleted = {0};

        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("run-"))
                    .forEach(dir -> {
                        if (tryDeleteIfOld(dir, cutoff)) {
                            deleted[0]++;
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to scan runner logs directory {}: {}", root, e.getMessage(), e);
        }
        return new CleanupResult(deleted[0]);
    }

    private boolean tryDeleteIfOld(Path dir, Instant cutoff) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(dir);
            if (lastModified.toInstant().isBefore(cutoff)) {
                deleteRecursively(dir);
                log.info("Deleted old runner log directory: {}", dir);
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to delete old runner log directory {}: {}", dir, e.getMessage(), e);
        }
        return false;
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

    public record CleanupResult(int deletedDirectories) {
    }
}
