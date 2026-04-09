package git.autoupdateservice.service.gitlab;

import git.autoupdateservice.config.GitlabProperties;
import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.domain.UpdateTask;
import git.autoupdateservice.service.AuditLogService;
import git.autoupdateservice.service.DependencyGraphChangeDetector;
import git.autoupdateservice.service.GitlabChangesService;
import git.autoupdateservice.service.QueueService;
import git.autoupdateservice.service.TaskChangedFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GitlabWebhookService {

    private final GitlabProperties gitlabProperties;
    private final GitlabPushEventParser gitlabPushEventParser;
    private final QueueService queueService;
    private final AuditLogService auditLogService;
    private final GitlabChangesService gitlabChangesService;
    private final TaskChangedFileService taskChangedFileService;
    private final DependencyGraphChangeDetector dependencyGraphChangeDetector;

    public GitlabWebhookResult handleWebhook(
            String token,
            String eventHeader,
            Map<String, Object> body,
            String clientIp
    ) {
        if (!StringUtils.hasText(token) || !token.equals(gitlabProperties.secret())) {
            auditLogService.warn(
                    LogType.WEBHOOK_RECEIVED,
                    "Webhook rejected: bad token",
                    "{\"eventHeader\":\"" + esc(eventHeader) + "\"}",
                    clientIp,
                    "gitlab",
                    null
            );
            return GitlabWebhookResult.unauthorized(Map.of("ok", false, "error", "bad token"));
        }

        String objectKind = gitlabPushEventParser.objectKind(body);
        String eventName = gitlabPushEventParser.eventName(body);

        auditLogService.info(
                LogType.WEBHOOK_RECEIVED,
                "Webhook received",
                "{\"eventHeader\":\"" + esc(eventHeader) + "\",\"object_kind\":\"" + esc(objectKind) + "\",\"event_name\":\"" + esc(eventName) + "\"}",
                clientIp,
                "gitlab",
                null
        );

        if (!gitlabPushEventParser.isPushEvent(eventHeader, body)) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("ignored", true);
            response.put("reason", "not push");
            response.put("eventHeader", eventHeader);
            response.put("object_kind", objectKind);
            response.put("event_name", eventName);
            return GitlabWebhookResult.ok(response);
        }

        GitlabPushEvent pushEvent = gitlabPushEventParser.parse(eventHeader, body);
        if (!StringUtils.hasText(pushEvent.projectPath())) {
            auditLogService.warn(
                    LogType.UNMAPPED_REPO_EVENT,
                    "Webhook push without project.path_with_namespace",
                    "{\"ref\":\"" + esc(pushEvent.ref()) + "\",\"after\":\"" + esc(pushEvent.commitSha()) + "\"}",
                    clientIp,
                    "gitlab",
                    null
            );
            return GitlabWebhookResult.ok(Map.of("ok", true, "enqueued", false, "reason", "projectPath is null"));
        }

        Optional<UpdateTask> enqueued = queueService.enqueueFromWebhook(pushEvent, clientIp);
        GitlabChangesService.FetchResult fetchResult = fetchFullChanges(pushEvent, clientIp);
        if (fetchResult != null) {
            enqueued.ifPresent(task -> storeFetchedChanges(task, fetchResult, clientIp));
            dependencyGraphChangeDetector.analyzeChangesAndMarkStale(pushEvent.projectPath(), fetchResult.files(), clientIp);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("enqueued", enqueued.isPresent());
        response.put("projectPath", pushEvent.projectPath());
        response.put("branch", pushEvent.branch());
        response.put("before", pushEvent.beforeSha());
        response.put("after", pushEvent.commitSha());
        response.put("commitAuthor", pushEvent.authorName());
        response.put("pusher", pushEvent.pusherLogin() != null ? pushEvent.pusherLogin() : pushEvent.pusherName());
        return GitlabWebhookResult.ok(response);
    }

    private void storeFetchedChanges(UpdateTask task, GitlabChangesService.FetchResult fetchResult, String clientIp) {
        try {
            taskChangedFileService.storeFetchedChanges(task, fetchResult, clientIp);
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.WEBHOOK_RECEIVED,
                    "Не удалось сохранить полный список измененных файлов: " + safeMessage(e),
                    "{\"taskId\":\"" + task.getId() + "\",\"projectPath\":\"" + esc(task.getProjectPath()) + "\"}",
                    clientIp,
                    "gitlab",
                    null
            );
        }
    }

    private GitlabChangesService.FetchResult fetchFullChanges(GitlabPushEvent pushEvent, String clientIp) {
        try {
            return gitlabChangesService.fetchFullChanges(
                    pushEvent.projectPath(),
                    pushEvent.beforeSha(),
                    pushEvent.commitSha()
            );
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.WEBHOOK_RECEIVED,
                    "Не удалось получить полный список измененных файлов из GitLab: " + safeMessage(e),
                    "{\"projectPath\":\"" + esc(pushEvent.projectPath())
                            + "\",\"fromSha\":\"" + esc(pushEvent.beforeSha())
                            + "\",\"toSha\":\"" + esc(pushEvent.commitSha()) + "\"}",
                    clientIp,
                    "gitlab",
                    null
            );
            return null;
        }
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (!StringUtils.hasText(message)) {
            message = error.getClass().getName();
        }
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
