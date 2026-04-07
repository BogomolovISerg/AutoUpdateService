package git.autoupdateservice.service;

import git.autoupdateservice.domain.TaskChangedFile;
import git.autoupdateservice.domain.UpdateTask;
import git.autoupdateservice.repo.TaskChangedFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskChangedFileService {

    private final GitlabChangesService gitlabChangesService;
    private final TaskChangedFileRepository taskChangedFileRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public void fetchAndStoreFullChanges(UpdateTask task, String clientIp) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("UpdateTask не задан");
        }
        if (!StringUtils.hasText(task.getProjectPath())) {
            throw new IllegalArgumentException("У задачи не заполнен projectPath");
        }
        if (!StringUtils.hasText(task.getCommitSha())) {
            throw new IllegalArgumentException("У задачи не заполнен commitSha");
        }
        if (!StringUtils.hasText(task.getBeforeSha())) {
            throw new IllegalArgumentException("У задачи не заполнен beforeSha");
        }

        UUID runId = UUID.randomUUID();
        try {
            GitlabChangesService.FetchResult fetchResult = gitlabChangesService.fetchFullChanges(
                    task.getProjectPath(),
                    task.getBeforeSha(),
                    task.getCommitSha()
            );

            taskChangedFileRepository.deleteByTask_Id(task.getId());

            List<TaskChangedFile> rows = new ArrayList<>();
            OffsetDateTime now = OffsetDateTime.now();
            for (GitlabChangesService.ChangedFile changedFile : fetchResult.files()) {
                TaskChangedFile row = new TaskChangedFile();
                row.setTask(task);
                row.setRunId(runId);
                row.setProjectPath(task.getProjectPath());
                row.setFromSha(task.getBeforeSha());
                row.setToSha(task.getCommitSha());
                row.setChangeType(changedFile.changeType());
                row.setOldPath(changedFile.oldPath());
                row.setNewPath(changedFile.newPath());
                row.setCreatedAt(now);
                rows.add(row);
            }

            if (!rows.isEmpty()) {
                taskChangedFileRepository.saveAll(rows);
            }

            auditLogService.info(
                    git.autoupdateservice.domain.LogType.WEBHOOK_RECEIVED,
                    "Полный список измененных файлов получен из GitLab",
                    "{\"taskId\":\"" + task.getId() + "\",\"projectPath\":\"" + esc(task.getProjectPath())
                            + "\",\"fromSha\":\"" + esc(task.getBeforeSha())
                            + "\",\"toSha\":\"" + esc(task.getCommitSha())
                            + "\",\"files\":" + rows.size()
                            + ",\"compareTimedOut\":" + fetchResult.compareTimedOut()
                            + ",\"usedCommitFallback\":" + fetchResult.usedCommitFallback()
                            + "}",
                    clientIp,
                    "gitlab",
                    null
            );
        } catch (Exception e) {
            auditLogService.warn(
                    git.autoupdateservice.domain.LogType.WEBHOOK_RECEIVED,
                    "Не удалось получить полный список измененных файлов из GitLab: " + safeMessage(e),
                    "{\"taskId\":\"" + task.getId() + "\",\"projectPath\":\"" + esc(task.getProjectPath())
                            + "\",\"fromSha\":\"" + esc(task.getBeforeSha())
                            + "\",\"toSha\":\"" + esc(task.getCommitSha()) + "\"}",
                    clientIp,
                    "gitlab",
                    null
            );
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static String safeMessage(Throwable e) {
        String s = e.getMessage();
        if (!StringUtils.hasText(s)) {
            s = e.getClass().getName();
        }
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
