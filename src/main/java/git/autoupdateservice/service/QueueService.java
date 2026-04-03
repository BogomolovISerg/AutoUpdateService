package git.autoupdateservice.service;

import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.RepoBindingRepository;
import git.autoupdateservice.repo.SettingsRepository;
import git.autoupdateservice.repo.UpdateTaskRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.core.NestedRuntimeException;

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
    public Optional<UpdateTask> enqueueFromWebhook(
            String projectPath,
            String branch,
            String beforeSha,
            String commitSha,
            String authorName,
            String authorLogin,
            String comment,
            String sourceKey,
            String clientIp
    ) {
        var bindingOpt = repoBindingRepository.findByProjectPathAndActiveTrue(projectPath);
        if (bindingOpt.isEmpty()) {
            auditLogService.warn(
                    LogType.UNMAPPED_REPO_EVENT,
                    "Webhook event for unmapped repo: " + projectPath,
                    "{\"projectPath\":\"" + esc(projectPath) + "\",\"commitSha\":\"" + esc(commitSha) + "\"}",
                    clientIp, "gitlab", null
            );
            return Optional.empty();
        }

        var binding = bindingOpt.get();
        settingsRepository.findById(1L).orElseThrow();

        LocalDate scheduledFor = LocalDate.now();
        UpdateTask t = new UpdateTask();
        t.setRepoBinding(binding);
        t.setTargetType(binding.getTargetType());
        t.setExtensionName(binding.getExtensionName());
        t.setRepoPath(binding.getRepoPath());
        t.setProjectPath(projectPath);
        t.setBranch(branch);
        t.setBeforeSha(beforeSha);
        t.setCommitSha(commitSha);
        t.setAuthorName(authorName);
        t.setAuthorLogin(authorLogin);
        t.setComment(comment);
        t.setSourceKey(sourceKey);
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
                    "{\"taskId\":\"" + saved.getId() + "\",\"projectPath\":\"" + esc(projectPath) + "\",\"beforeSha\":\"" + esc(beforeSha) + "\",\"commitSha\":\"" + esc(commitSha) + "\",\"scheduledFor\":\"" + scheduledFor + "\"}",
                    clientIp, "gitlab", null
            );

            return Optional.of(saved);
        } catch (DataIntegrityViolationException e) {
            // чаще всего: duplicate source_key
            auditLogService.warn(
                    LogType.TASK_ENQUEUED, // если хотите — заведите отдельный LogType, например TASK_DUPLICATE_IGNORED
                    "Task not inserted (duplicate/constraint): " + safeMsg(e),
                    "{\"sourceKey\":\"" + esc(sourceKey) + "\",\"projectPath\":\"" + esc(projectPath) + "\",\"commitSha\":\"" + esc(commitSha) + "\"}",
                    clientIp, "gitlab", null
            );
            return Optional.empty();
        }
    }

    @Transactional
    public UpdateTask toggleCancel(UUID taskId, String clientIp, String actor) {
        UpdateTask t = updateTaskRepository.findById(taskId).orElseThrow();
        if (t.getStatus() == TaskStatus.UPDATED) {
            throw new IllegalStateException("Нельзя менять статус UPDATED");
        }

        TaskStatus before = t.getStatus();
        if (before == TaskStatus.NEW) t.setStatus(TaskStatus.CANCELED);
        else if (before == TaskStatus.CANCELED) t.setStatus(TaskStatus.NEW);

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
}