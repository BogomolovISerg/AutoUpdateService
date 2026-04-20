package git.autoupdateservice.service;

import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.RepoBindingRepository;
import git.autoupdateservice.repo.SettingsRepository;
import git.autoupdateservice.repo.UpdateTaskRepository;
import git.autoupdateservice.service.gitlab.GitlabPushEvent;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.NestedRuntimeException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final RepoBindingRepository repoBindingRepository;
    private final UpdateTaskRepository updateTaskRepository;
    private final SettingsRepository settingsRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public Optional<UpdateTask> enqueueFromWebhook(GitlabPushEvent event, String clientIp) {
        var bindingOpt = repoBindingRepository.findByProjectPathAndActiveTrue(event.projectPath());
        if (bindingOpt.isEmpty()) {
            auditLogService.warn(
                    LogType.UNMAPPED_REPO_EVENT,
                    "Webhook event for unmapped repo: " + event.projectPath(),
                    "{\"projectPath\":\"" + esc(event.projectPath()) + "\",\"commitSha\":\"" + esc(event.commitSha()) + "\"}",
                    clientIp, "gitlab", null
            );
            return Optional.empty();
        }

        var binding = bindingOpt.get();
        var settings = settingsRepository.findById(1L).orElseThrow();

        LocalDate scheduledFor = LocalDate.now(resolveZone(settings.getTimezone()));
        UpdateTask t = new UpdateTask();
        t.setRepoBinding(binding);
        t.setTargetType(binding.getTargetType());
        t.setExtensionName(binding.getExtensionName());
        t.setExtensionPlanFileKey(binding.getExtensionPlanFileKey());
        t.setRepoPath(binding.getRepoPath());
        t.setProjectPath(event.projectPath());
        t.setGitProjectId(event.projectId());
        t.setGitProjectName(event.projectName());
        t.setGitRef(event.ref());
        t.setGitCheckoutSha(event.checkoutSha());
        t.setGitEventName(event.eventName());
        t.setGitObjectKind(event.objectKind());
        t.setGitUserName(event.pusherName());
        t.setGitUserUsername(event.pusherLogin());
        t.setGitUserEmail(event.userEmail());
        t.setGitTotalCommitsCount(event.totalCommitsCount());
        t.setGitWebhookReceivedAt(OffsetDateTime.now());
        t.setBranch(event.branch());
        t.setBeforeSha(event.beforeSha());
        t.setCommitSha(event.commitSha());
        t.setAuthorName(event.authorName());
        t.setAuthorLogin(event.authorLogin());
        t.setComment(event.comment());
        t.setSourceKey(event.sourceKey());
        t.setStatus(TaskStatus.NEW);
        t.setScheduledFor(scheduledFor);
        t.setCreatedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());

        try {
            // saveAndFlush — чтобы constraint/ошибки не "терялись" и вылезали сразу
            UpdateTask saved = updateTaskRepository.saveAndFlush(t);

            auditLogService.info(
                    LogType.TASK_ENQUEUED,
                    "Task enqueued: " + binding.getTargetType() +
                            (binding.getExtensionName() != null ? " (" + binding.getExtensionName() + ")" : ""),
                    "{\"taskId\":\"" + saved.getId() + "\",\"projectPath\":\"" + esc(event.projectPath()) + "\",\"beforeSha\":\"" + esc(event.beforeSha()) + "\",\"commitSha\":\"" + esc(event.commitSha()) + "\",\"scheduledFor\":\"" + scheduledFor + "\"}",
                    clientIp, "gitlab", null
            );

            return Optional.of(saved);
        } catch (DataIntegrityViolationException e) {
            // чаще всего: duplicate source_key
            Optional<UpdateTask> existing = updateTaskRepository.findBySourceKey(event.sourceKey());
            auditLogService.warn(
                    LogType.TASK_ENQUEUED, // если хотите — заведите отдельный LogType, например TASK_DUPLICATE_IGNORED
                    "Task not inserted (duplicate/constraint), existing task will be reused: " + safeMsg(e),
                    "{\"sourceKey\":\"" + esc(event.sourceKey()) + "\",\"projectPath\":\"" + esc(event.projectPath()) + "\",\"commitSha\":\"" + esc(event.commitSha()) + "\",\"existingTaskId\":\"" + existing.map(x -> String.valueOf(x.getId())).orElse("") + "\"}",
                    clientIp, "gitlab", null
            );
            return existing;
        }
    }

    @Transactional
    public UpdateTask toggleCancel(UUID taskId, String clientIp, String actor) {
        UpdateTask t = updateTaskRepository.findById(taskId).orElseThrow();
        if (t.getStatus() == TaskStatus.UPDATED) {
            throw new IllegalStateException("Нельзя менять статус UPDATED");
        }

        TaskStatus before = t.getStatus();
        if (before == TaskStatus.CANCELED) t.setStatus(TaskStatus.NEW);
        else t.setStatus(TaskStatus.CANCELED);

        t.setUpdatedAt(OffsetDateTime.now());
        UpdateTask saved = updateTaskRepository.saveAndFlush(t);

        auditLogService.info(
                LogType.TASK_STATUS_CHANGED,
                "Task status changed: " + before + " -> " + saved.getStatus(),
                "{\"taskId\":\"" + taskId + "\"}",
                clientIp, actor, null
        );

        return saved;
    }

    public long pendingNewCount() {
        return updateTaskRepository.countByStatus(TaskStatus.NEW);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static String safeMsg(NestedRuntimeException e) {
        String msg = (e.getMostSpecificCause() != null) ? e.getMostSpecificCause().getMessage() : e.getMessage();
        if (msg == null) msg = "unknown";
        msg = msg.replace("\n", " ").replace("\r", " ");
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private static ZoneId resolveZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }
}
