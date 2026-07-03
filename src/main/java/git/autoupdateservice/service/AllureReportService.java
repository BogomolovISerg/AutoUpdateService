package git.autoupdateservice.service;

import git.autoupdateservice.config.RunnerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AllureReportService {

    private final RunnerProperties runnerProperties;

    public boolean hasReport(UUID runId) {
        return runId != null && Files.isRegularFile(resolveIndexFile(runId));
    }

    public Path resolveIndexFile(UUID runId) {
        return resolveReportDir(runId).resolve("index.html").normalize();
    }

    public Path resolveReportFile(UUID runId, String relativePath) {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalArgumentException("relativePath is required");
        }

        String safeRelativePath = stripLeadingSeparators(relativePath);
        Path reportDir = resolveReportDir(runId);
        Path resolved = reportDir.resolve(safeRelativePath).normalize();
        if (!resolved.startsWith(reportDir)) {
            throw new IllegalArgumentException("Invalid report path");
        }
        return resolved;
    }

    private String stripLeadingSeparators(String path) {
        String result = path;
        while (result.startsWith("/") || result.startsWith("\\")) {
            result = result.substring(1);
        }
        return result;
    }

    private Path resolveReportDir(UUID runId) {
        return resolveLogsRoot()
                .resolve("run-" + runId)
                .resolve("allure")
                .normalize();
    }

    private Path resolveLogsRoot() {
        String logDir = runnerProperties.logDir();
        if (!StringUtils.hasText(logDir)) {
            logDir = "./runner-logs";
        }
        return Path.of(logDir).toAbsolutePath().normalize();
    }
}
