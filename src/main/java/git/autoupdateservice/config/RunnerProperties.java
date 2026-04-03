package git.autoupdateservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runner")
public record RunnerProperties(
        String binary,
        String rasAddress,
        String racPath,
        String baseName,
        String ibConnection,
        String dbUser,
        String dbPassword,
        String repoUser,
        String repoPassword,
        String logDir,
        String windowsCodePage,
        String stepsFile,

        // Optional overrides / fallbacks (may be set via application.properties / env)
        String lockMessage,
        String uccode,
        String mainRepoPath,
        String extRepoPath,

        // Some installations prefer a different 1C connection format for repo operations (e.g. /S...).
        // Bindable from runner.ib-connectionrepo / runner.ib-connection-repo / RUNNER_CONNECTIONREPO, etc.
        String ibConnectionrepo,
        int keepDays
) {}
