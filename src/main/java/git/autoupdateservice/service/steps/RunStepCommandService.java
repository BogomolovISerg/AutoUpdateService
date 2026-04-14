package git.autoupdateservice.service.steps;

import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.domain.ExecutionRun;
import git.autoupdateservice.domain.Settings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RunStepCommandService {

    private static final Pattern VAR = Pattern.compile("\\{\\{([\\w.-]+)}}");

    private final RunnerProperties runnerProperties;

    public Map<String, String> buildContext(
            ExecutionRun run,
            Settings settings,
            Map<String, String> planSettings,
            boolean needMain,
            String mainRepoPath,
            String extRepoPath,
            String ext,
            String extFile,
            Integer attempt,
            Path runDir,
            Path workDir
    ) {
        Map<String, String> context = new HashMap<>();

        String binary = resolveSetting(planSettings, runnerProperties.binary(), "binary");
        context.put("binary", nvl(binary));

        String rasAddress = resolveSetting(planSettings, runnerProperties.rasAddress(), "ras", "ras-address");
        context.put("ras", nvl(rasAddress));
        context.put("ras-address", nvl(rasAddress));

        String racPath = resolveSetting(planSettings, runnerProperties.racPath(), "rac", "rac-path");
        context.put("rac", nvl(racPath));
        context.put("rac-path", nvl(racPath));

        String baseName = resolveSetting(planSettings, runnerProperties.baseName(), "db", "base-name");
        context.put("db", nvl(baseName));
        context.put("base-name", nvl(baseName));

        String dbUser = resolveSetting(planSettings, runnerProperties.dbUser(), "dbUser", "db-user");
        context.put("dbUser", nvl(dbUser));
        context.put("db-user", nvl(dbUser));

        String dbPassword = resolveSetting(planSettings, runnerProperties.dbPassword(), "dbPwd", "db-password", "db-pwd");
        context.put("dbPwd", nvl(dbPassword));
        context.put("db-password", nvl(dbPassword));
        context.put("db-pwd", nvl(dbPassword));

        String ibConnection = resolveSetting(planSettings, runnerProperties.ibConnection(), "ibConnection", "ib-connection");
        context.put("ibConnection", nvl(ibConnection));
        context.put("ib-connection", nvl(ibConnection));

        String repoUser = resolveSetting(planSettings, runnerProperties.repoUser(), "repoUser", "repo-user");
        context.put("repoUser", nvl(repoUser));
        context.put("repo-user", nvl(repoUser));

        String repoPassword = resolveSetting(planSettings, runnerProperties.repoPassword(), "repoPwd", "repo-password", "repo-pwd");
        context.put("repoPwd", nvl(repoPassword));
        context.put("repo-password", nvl(repoPassword));
        context.put("repo-pwd", nvl(repoPassword));

        String uccode = unquote(resolveSetting(planSettings, firstNonBlank(runnerProperties.uccode(), settings.getUccode()), "uccode", "uc-code"));
        String lockMessage = unquote(resolveSetting(planSettings, firstNonBlank(runnerProperties.lockMessage(), settings.getLockMessage()), "lockMessage", "lock-message"));
        context.put("uccode", nvl(uccode));
        context.put("lockMessage", nvl(lockMessage));

        String ibConnectionRepo = resolveSetting(planSettings,
                firstNonBlank(runnerProperties.ibConnectionrepo(), runnerProperties.ibConnection()),
                "ibConnectionRepo", "ibConnectionrepo", "ib-connectionrepo", "ib-connection-repo");
        context.put("ibConnectionRepo", nvl(ibConnectionRepo));
        context.put("ibConnectionrepo", nvl(ibConnectionRepo));
        context.put("ib-connectionrepo", nvl(ibConnectionRepo));
        context.put("ib-connection-repo", nvl(ibConnectionRepo));

        context.put("needMain", String.valueOf(needMain));
        context.put("mainRepoPath", nvl(firstNonBlank(mainRepoPath, resolveSetting(planSettings, runnerProperties.mainRepoPath(), "mainRepoPath", "main-repo-path"))));
        context.put("extRepoPath", nvl(firstNonBlank(extRepoPath, resolveSetting(planSettings, runnerProperties.extRepoPath(), "extRepoPath", "ext-repo-path"))));
        context.put("ext", nvl(ext));
        context.put("extFile", nvl(extFile));
        context.put("attempt", attempt == null ? "" : String.valueOf(attempt));
        context.put("runId", String.valueOf(run.getId()));
        context.put("runDir", runDir.toAbsolutePath().toString());
        context.put("workDir", workDir.toAbsolutePath().toString());
        context.put("log-dir", runDir.toAbsolutePath().toString());
        context.put("logDir", runDir.toAbsolutePath().toString());
        context.put("log-dir-root", nvl(runnerProperties.logDir()));
        context.put("logDirRoot", nvl(runnerProperties.logDir()));
        addPlanSettings(context, planSettings);

        return context;
    }

    public String planValue(Map<String, String> planSettings, String... aliases) {
        return resolveSetting(planSettings, null, aliases);
    }

    public List<String> expandCommand(List<String> source, Map<String, String> context, boolean strict) {
        if (source == null) {
            return List.of();
        }

        List<String> result = new ArrayList<>(source.size() * 2);
        for (int index = 0; index < source.size(); index++) {
            String raw = source.get(index);
            if (raw == null) {
                continue;
            }

            String rendered = renderTemplate(raw, context, strict).trim();
            if (rendered.isBlank()) {
                continue;
            }

            int ws = firstWhitespace(rendered);
            if (ws < 0) {
                result.add(rendered);
                continue;
            }

            String head = rendered.substring(0, ws).trim();
            String tail = rendered.substring(ws).trim();
            boolean shouldSplit = head.startsWith("--") || isCommandShortcut(index, head);
            if (shouldSplit) {
                if (!head.isBlank()) {
                    result.add(head);
                }
                if (!tail.isBlank()) {
                    result.add(tail);
                }
                continue;
            }

            result.add(rendered);
        }

        for (int index = 0; index < result.size(); index++) {
            String value = result.get(index);
            if (value == null) {
                continue;
            }
            String normalized = value.trim();
            if (normalized.contains("{{")) {
                throw new IllegalStateException("Unresolved token in command arg: " + normalized);
            }
            if ("--ib-connection".equalsIgnoreCase(normalized)) {
                result.set(index, "--ibconnection");
            }
            if ("--ib-connectionrepo".equalsIgnoreCase(normalized)
                    || "--ib-connection-repo".equalsIgnoreCase(normalized)) {
                result.set(index, "--ibconnectionrepo");
            }
        }

        return result;
    }

    public String render(String template, Map<String, String> context) {
        return renderTemplate(template, context, false);
    }

    private String renderTemplate(String template, Map<String, String> context, boolean strict) {
        if (template == null) {
            return null;
        }
        var matcher = VAR.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = context.get(key);
            if (value == null) {
                if (strict) {
                    throw new IllegalStateException("Unknown token {{" + key + "}} in steps plan");
                }
                value = matcher.group(0);
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolveSetting(Map<String, String> planSettings, String fallback, String... aliases) {
        if (planSettings != null && !planSettings.isEmpty()) {
            for (String alias : aliases) {
                for (String candidate : settingCandidates(alias)) {
                    String value = planSettings.get(candidate);
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        return fallback;
    }

    private void addPlanSettings(Map<String, String> context, Map<String, String> planSettings) {
        if (planSettings == null || planSettings.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : planSettings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            context.putIfAbsent(key, value);

            String normalized = key.startsWith("runner.") ? key.substring("runner.".length()) : key;
            context.putIfAbsent(normalized, value);
            context.putIfAbsent(toCamel(normalized), value);
            context.putIfAbsent(toKebab(normalized), value);
        }
    }

    private List<String> settingCandidates(String alias) {
        String trimmed = alias == null ? "" : alias.trim();
        if (trimmed.isBlank()) {
            return List.of();
        }
        String kebab = toKebab(trimmed);
        String camel = toCamel(trimmed);
        return List.of(
                trimmed,
                kebab,
                camel,
                "runner." + trimmed,
                "runner." + kebab,
                "runner." + camel
        );
    }

    private static boolean isCommandShortcut(int index, String head) {
        if (head == null || head.isBlank()) {
            return false;
        }

        String normalized = head.trim();
        if (normalized.startsWith("--") || normalized.startsWith("/")) {
            return false;
        }

        return index == 1;
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String toKebab(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.indexOf('-') >= 0) {
            return normalized;
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            if (Character.isUpperCase(ch)) {
                if (index > 0) {
                    builder.append('-');
                }
                builder.append(Character.toLowerCase(ch));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static String toCamel(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.indexOf('-') < 0) {
            return normalized;
        }
        String[] parts = normalized.split("-");
        StringBuilder builder = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            if (parts[index].isEmpty()) {
                continue;
            }
            builder.append(Character.toUpperCase(parts[index].charAt(0)));
            if (parts[index].length() > 1) {
                builder.append(parts[index].substring(1));
            }
        }
        return builder.toString();
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }
}
