package git.autoupdateservice.web;

import git.autoupdateservice.config.GitlabProperties;
import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.service.AuditLogService;
import git.autoupdateservice.service.DependencyGraphChangeDetector;
import git.autoupdateservice.service.QueueService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/gitlab")
public class GitlabWebhookController {

    private final GitlabProperties gitlabProperties;
    private final QueueService queueService;
    private final AuditLogService auditLogService;
    private final DependencyGraphChangeDetector dependencyGraphChangeDetector;

    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String event,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String ip = IpUtil.clientIp(request);

        // 1) Token check
        if (!StringUtils.hasText(token) || !token.equals(gitlabProperties.secret())) {
            auditLogService.warn(
                    LogType.WEBHOOK_RECEIVED,
                    "Webhook rejected: bad token",
                    "{\"eventHeader\":\"" + esc(event) + "\"}",
                    ip, "gitlab", null
            );
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "bad token"));
        }

        // 2) Detect push event (header OR body)
        String objectKind = (String) body.get("object_kind");
        String eventName = (String) body.get("event_name");

        boolean isPush =
                "push".equalsIgnoreCase(objectKind) ||
                        "push".equalsIgnoreCase(eventName) ||
                        "Push Hook".equalsIgnoreCase(event) ||
                        "Push Event".equalsIgnoreCase(event) ||
                        "push".equalsIgnoreCase(event);

        auditLogService.info(
                LogType.WEBHOOK_RECEIVED,
                "Webhook received",
                "{\"eventHeader\":\"" + esc(event) + "\",\"object_kind\":\"" + esc(objectKind) + "\",\"event_name\":\"" + esc(eventName) + "\"}",
                ip, "gitlab", null
        );

        if (!isPush) {
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "ignored", true,
                    "reason", "not push",
                    "eventHeader", event,
                    "object_kind", objectKind,
                    "event_name", eventName
            ));
        }

        // 3) Parse payload
        Map<String, Object> project = asMap(body.get("project"));
        String projectPath = project != null ? (String) project.get("path_with_namespace") : null;

        String ref = (String) body.get("ref");
        String branch = parseBranch(ref);

        String beforeSha = (String) body.get("before");
        String commitSha = (String) body.get("after");

        // кто сделал push (может отличаться от автора коммита)
        String pusherName = (String) body.get("user_name");
        String pusherLogin = (String) body.get("user_username");

        // автор и сообщение head-коммита (последний коммит пуша)
        CommitMeta cm = extractCommitMeta(body, commitSha);
        String comment = StringUtils.hasText(cm.message) ? cm.message : "";

        // Выбор автора (приоритет):
        // 1) author из payload
        // 2) если не получилось — берём из комментария (последнее слово)
        // 3) если и это не получилось — пушер
        String authorName = firstNonBlank(
                cm.authorName,
                extractAuthorFromComment(comment),
                pusherName,
                pusherLogin
        );

        // login сохраняем как "кто пушил" (для подсказки/аудита)
        String authorLogin = StringUtils.hasText(pusherLogin) ? pusherLogin : null;

        if (!StringUtils.hasText(projectPath)) {
            auditLogService.warn(
                    LogType.UNMAPPED_REPO_EVENT,
                    "Webhook push without project.path_with_namespace",
                    "{\"ref\":\"" + esc(ref) + "\",\"after\":\"" + esc(commitSha) + "\"}",
                    ip, "gitlab", null
            );
            return ResponseEntity.ok(Map.of("ok", true, "enqueued", false, "reason", "projectPath is null"));
        }

        String sourceKey = projectPath + ":" + (commitSha == null ? "unknown" : commitSha);

        var enqueued = queueService.enqueueFromWebhook(
                projectPath,
                branch,
                beforeSha,
                commitSha,
                authorName,
                authorLogin,
                comment,
                sourceKey,
                ip
        );

        dependencyGraphChangeDetector.analyzePushAndMarkStale(projectPath, beforeSha, commitSha, ip);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "enqueued", enqueued.isPresent(),
                "projectPath", projectPath,
                "branch", branch,
                "before", beforeSha,
                "after", commitSha,
                "commitAuthor", authorName,
                "pusher", (pusherLogin != null ? pusherLogin : pusherName)
        ));
    }

    private static CommitMeta extractCommitMeta(Map<String, Object> body, String afterSha) {
        // 1) commits[]: пытаемся найти именно head-коммит по afterSha
        Object commitsObj = body.get("commits");
        if (commitsObj instanceof List<?> list && !list.isEmpty()) {
            Map<String, Object> last = null;
            for (Object it : list) {
                if (it instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cm = (Map<String, Object>) m;
                    last = cm;
                    String id = asString(cm.get("id"));
                    String sha = asString(cm.get("sha"));
                    if (StringUtils.hasText(afterSha) && (afterSha.equalsIgnoreCase(id) || afterSha.equalsIgnoreCase(sha))) {
                        return fromCommitMap(cm);
                    }
                }
            }
            // если afterSha не нашли — берём последний коммит массива
            if (last != null) return fromCommitMap(last);
        }

        // 2) last_commit
        Map<String, Object> lastCommit = asMap(body.get("last_commit"));
        if (lastCommit != null) return fromCommitMap(lastCommit);

        // 3) fallback
        return new CommitMeta(null, asString(body.get("message")));
    }

    private static CommitMeta fromCommitMap(Map<String, Object> cm) {
        String msg = asString(cm.get("message"));

        String authorName = asString(cm.get("author_name"));

        // GitLab часто шлёт author как объект
        if (!StringUtils.hasText(authorName)) {
            Map<String, Object> authorObj = asMap(cm.get("author"));
            if (authorObj != null) authorName = asString(authorObj.get("name"));
        }

        // иногда author может быть строкой "Имя <email>"
        if (!StringUtils.hasText(authorName)) {
            Object author = cm.get("author");
            if (author instanceof String s) authorName = parseNameFromAngleBrackets(s);
        }

        // запасной вариант: committer
        if (!StringUtils.hasText(authorName)) {
            authorName = asString(cm.get("committer_name"));
        }
        if (!StringUtils.hasText(authorName)) {
            Map<String, Object> committerObj = asMap(cm.get("committer"));
            if (committerObj != null) authorName = asString(committerObj.get("name"));
        }

        return new CommitMeta(blankToNull(authorName), blankToNull(msg));
    }

    private static String extractAuthorFromComment(String comment) {
        if (!StringUtils.hasText(comment)) return null;

        // берём последнее "значимое" слово в конце текста
        String s = comment.strip();
        if (s.isEmpty()) return null;

        // убираем завершающие знаки препинания/разделители
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (Character.isLetter(c)) break;
            end--;
        }
        if (end <= 0) return null;

        int start = end - 1;
        while (start >= 0) {
            char c = s.charAt(start);
            if (Character.isLetter(c) || c == '-' || c == '’' || c == '\'') {
                start--;
                continue;
            }
            break;
        }
        String token = s.substring(start + 1, end).trim();
        token = token.replaceAll("^[\\-’']+|[\\-’']+$", "");

        // минимальная защита от мусора
        if (token.length() < 2) return null;
        return token;
    }

    private static String parseNameFromAngleBrackets(String s) {
        if (!StringUtils.hasText(s)) return null;
        String t = s.strip();
        int idx = t.indexOf('<');
        if (idx > 0) t = t.substring(0, idx).strip();
        return blankToNull(t);
    }

    private record CommitMeta(String authorName, String message) {}

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
    }

    private static String parseBranch(String ref) {
        if (ref == null) return null;
        String p = "refs/heads/";
        return ref.startsWith(p) ? ref.substring(p.length()) : ref;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
