package git.autoupdateservice.service.gitlab;

import git.autoupdateservice.config.GitChangeType;
import git.autoupdateservice.service.GitlabChangesService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GitlabPushEventParser {

    public boolean isPushEvent(String eventHeader, Map<String, Object> body) {
        String objectKind = objectKind(body);
        String eventName = eventName(body);
        return "push".equalsIgnoreCase(objectKind)
                || "push".equalsIgnoreCase(eventName)
                || "Push Hook".equalsIgnoreCase(eventHeader)
                || "Push Event".equalsIgnoreCase(eventHeader)
                || "push".equalsIgnoreCase(eventHeader);
    }

    public String objectKind(Map<String, Object> body) {
        return body == null ? null : asString(body.get("object_kind"));
    }

    public String eventName(Map<String, Object> body) {
        return body == null ? null : asString(body.get("event_name"));
    }

    public List<GitlabChangesService.ChangedFile> extractChangedFiles(Map<String, Object> body) {
        if (body == null) {
            return List.of();
        }

        LinkedHashMap<String, GitlabChangesService.ChangedFile> files = new LinkedHashMap<>();
        Object commitsObj = body.get("commits");
        if (commitsObj instanceof List<?> commits) {
            for (Object item : commits) {
                Map<String, Object> commit = asMap(item);
                if (commit == null) {
                    continue;
                }
                collectFiles(files, commit.get("added"), GitChangeType.ADDED, false);
                collectFiles(files, commit.get("modified"), GitChangeType.MODIFIED, false);
                collectFiles(files, commit.get("removed"), GitChangeType.REMOVED, true);
            }
        }

        return new ArrayList<>(files.values());
    }

    public GitlabPushEvent parse(String eventHeader, Map<String, Object> body) {
        Map<String, Object> project = asMap(body.get("project"));
        String projectPath = project != null ? asString(project.get("path_with_namespace")) : null;
        String ref = asString(body.get("ref"));
        String branch = parseBranch(ref);
        String beforeSha = asString(body.get("before"));
        String commitSha = asString(body.get("after"));
        String pusherName = asString(body.get("user_name"));
        String pusherLogin = asString(body.get("user_username"));
        CommitMeta commitMeta = extractCommitMeta(body, commitSha);
        String comment = StringUtils.hasText(commitMeta.message()) ? commitMeta.message() : "";
        String authorName = firstNonBlank(
                commitMeta.authorName(),
                extractAuthorFromComment(comment),
                pusherName,
                pusherLogin
        );
        String authorLogin = StringUtils.hasText(pusherLogin) ? pusherLogin : null;

        Long projectId = project != null ? toLong(project.get("id")) : toLong(body.get("project_id"));
        String projectName = project != null ? asString(project.get("name")) : null;

        return new GitlabPushEvent(
                eventHeader,
                objectKind(body),
                eventName(body),
                projectPath,
                projectId,
                projectName,
                ref,
                branch,
                beforeSha,
                commitSha,
                asString(body.get("checkout_sha")),
                pusherName,
                pusherLogin,
                asString(body.get("user_email")),
                toInteger(body.get("total_commits_count")),
                authorName,
                authorLogin,
                comment,
                buildSourceKey(projectPath, commitSha)
        );
    }

    private String buildSourceKey(String projectPath, String commitSha) {
        if (!StringUtils.hasText(projectPath)) {
            return null;
        }
        return projectPath + ":" + (commitSha == null ? "unknown" : commitSha);
    }

    private CommitMeta extractCommitMeta(Map<String, Object> body, String afterSha) {
        Object commitsObj = body.get("commits");
        if (commitsObj instanceof List<?> list && !list.isEmpty()) {
            Map<String, Object> last = null;
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> commit = (Map<String, Object>) map;
                    last = commit;
                    String id = asString(commit.get("id"));
                    String sha = asString(commit.get("sha"));
                    if (StringUtils.hasText(afterSha) && (afterSha.equalsIgnoreCase(id) || afterSha.equalsIgnoreCase(sha))) {
                        return fromCommitMap(commit);
                    }
                }
            }
            if (last != null) {
                return fromCommitMap(last);
            }
        }

        Map<String, Object> lastCommit = asMap(body.get("last_commit"));
        if (lastCommit != null) {
            return fromCommitMap(lastCommit);
        }

        return new CommitMeta(null, asString(body.get("message")));
    }

    private CommitMeta fromCommitMap(Map<String, Object> commit) {
        String message = asString(commit.get("message"));
        String authorName = asString(commit.get("author_name"));

        if (!StringUtils.hasText(authorName)) {
            Map<String, Object> authorObj = asMap(commit.get("author"));
            if (authorObj != null) {
                authorName = asString(authorObj.get("name"));
            }
        }

        if (!StringUtils.hasText(authorName)) {
            Object author = commit.get("author");
            if (author instanceof String value) {
                authorName = parseNameFromAngleBrackets(value);
            }
        }

        if (!StringUtils.hasText(authorName)) {
            authorName = asString(commit.get("committer_name"));
        }
        if (!StringUtils.hasText(authorName)) {
            Map<String, Object> committerObj = asMap(commit.get("committer"));
            if (committerObj != null) {
                authorName = asString(committerObj.get("name"));
            }
        }

        return new CommitMeta(blankToNull(authorName), blankToNull(message));
    }

    private String extractAuthorFromComment(String comment) {
        if (!StringUtils.hasText(comment)) {
            return null;
        }

        String value = comment.strip();
        if (value.isEmpty()) {
            return null;
        }

        int end = value.length();
        while (end > 0) {
            char current = value.charAt(end - 1);
            if (Character.isLetter(current)) {
                break;
            }
            end--;
        }
        if (end <= 0) {
            return null;
        }

        int start = end - 1;
        while (start >= 0) {
            char current = value.charAt(start);
            if (Character.isLetter(current) || current == '-' || current == '’' || current == '\'') {
                start--;
                continue;
            }
            break;
        }

        String token = value.substring(start + 1, end).trim();
        token = token.replaceAll("^[\\-’']+|[\\-’']+$", "");
        return token.length() < 2 ? null : token;
    }

    private String parseNameFromAngleBrackets(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.strip();
        int index = normalized.indexOf('<');
        if (index > 0) {
            normalized = normalized.substring(0, index).strip();
        }
        return blankToNull(normalized);
    }

    private String parseBranch(String ref) {
        if (ref == null) {
            return null;
        }
        String prefix = "refs/heads/";
        return ref.startsWith(prefix) ? ref.substring(prefix.length()) : ref;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (value instanceof Map<?, ?> map) ? (Map<String, Object>) map : null;
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private record CommitMeta(String authorName, String message) {
    }

    private void collectFiles(
            Map<String, GitlabChangesService.ChangedFile> target,
            Object filesObj,
            GitChangeType changeType,
            boolean removed
    ) {
        if (!(filesObj instanceof List<?> files)) {
            return;
        }
        for (Object item : files) {
            String path = asString(item);
            if (!StringUtils.hasText(path)) {
                continue;
            }
            GitlabChangesService.ChangedFile changedFile = removed
                    ? new GitlabChangesService.ChangedFile(changeType, path, null)
                    : new GitlabChangesService.ChangedFile(changeType, null, path);
            target.putIfAbsent(changeType + "|" + path, changedFile);
        }
    }
}
