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

        context.put("binary", nvl(runnerProperties.binary()));
        context.put("ras", nvl(runnerProperties.rasAddress()));
        context.put("rac", nvl(runnerProperties.racPath()));
        context.put("db", nvl(runnerProperties.baseName()));
        context.put("dbUser", nvl(runnerProperties.dbUser()));
        context.put("dbPwd", nvl(runnerProperties.dbPassword()));
        context.put("ibConnection", nvl(runnerProperties.ibConnection()));
        context.put("repoUser", nvl(runnerProperties.repoUser()));
        context.put("repoPwd", nvl(runnerProperties.repoPassword()));

        context.put("ras-address", nvl(runnerProperties.rasAddress()));
        context.put("rac-path", nvl(runnerProperties.racPath()));
        context.put("base-name", nvl(runnerProperties.baseName()));
        context.put("db-user", nvl(runnerProperties.dbUser()));
        context.put("db-password", nvl(runnerProperties.dbPassword()));
        context.put("ib-connection", nvl(runnerProperties.ibConnection()));
        context.put("repo-user", nvl(runnerProperties.repoUser()));
        context.put("repo-password", nvl(runnerProperties.repoPassword()));

        String uccode = unquote(firstNonBlank(runnerProperties.uccode(), settings.getUccode()));
        String lockMessage = unquote(firstNonBlank(runnerProperties.lockMessage(), settings.getLockMessage()));
        context.put("uccode", nvl(uccode));
        context.put("lockMessage", nvl(lockMessage));

        String ibConnectionRepo = firstNonBlank(runnerProperties.ibConnectionrepo(), runnerProperties.ibConnection());
        context.put("ibConnectionRepo", nvl(ibConnectionRepo));
        context.put("ibConnectionrepo", nvl(ibConnectionRepo));
        context.put("ib-connectionrepo", nvl(ibConnectionRepo));
        context.put("ib-connection-repo", nvl(ibConnectionRepo));

        context.put("needMain", String.valueOf(needMain));
        context.put("mainRepoPath", nvl(mainRepoPath));
        context.put("extRepoPath", nvl(extRepoPath));
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

        return context;
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
