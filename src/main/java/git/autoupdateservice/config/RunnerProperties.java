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
        String display,
        String xauthority,
        Boolean linuxGuiEnvironmentEnabled,

        String lockMessage,
        String uccode,
        String mainRepoPath,
        String extRepoPath,
        String ext,

        String ibConnectionrepo,
        String testPlanFile,
        String productionPlanFile
) {}
