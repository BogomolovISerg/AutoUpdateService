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
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskChangedFileService {

    private final TaskChangedFileRepository taskChangedFileRepository;
    private final AuditLogService auditLogService;
    private final DependencyGraphChangeDetector dependencyGraphChangeDetector;
    private final ChangedObjectService changedObjectService;

    @Transactional
    public void storeFetchedChanges(UpdateTask task, GitlabChangesService.FetchResult fetchResult, String clientIp) {
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
        if (fetchResult == null) {
            throw new IllegalArgumentException("FetchResult не задан");
        }

        UUID runId = UUID.randomUUID();
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
    }

    @Transactional
    public void registerDirectObjectsFromStoredChanges(List<UpdateTask> tasks, UUID runId) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        int changedFiles = 0;
        int directObjects = 0;
        int savedObjects = 0;

        for (UpdateTask task : tasks) {
            if (task == null || task.getId() == null) {
                continue;
            }

            List<TaskChangedFile> rows = taskChangedFileRepository.findByTask_IdOrderByCreatedAtAsc(task.getId());
            if (rows.isEmpty()) {
                continue;
            }

            List<GitlabChangesService.ChangedFile> files = rows.stream()
                    .map(this::toChangedFile)
                    .filter(Objects::nonNull)
                    .toList();
            changedFiles += files.size();

            List<DependencyGraphChangeDetector.DirectObjectHit> hits = dependencyGraphChangeDetector.extractDirectObjects(files);
            directObjects += hits.size();
            savedObjects += changedObjectService.registerDirectObjects(task, hits, null);
        }

        if (changedFiles > 0) {
            auditLogService.info(
                    git.autoupdateservice.domain.LogType.WEBHOOK_RECEIVED,
                    "Проверка прямых объектов по сохраненному списку измененных файлов выполнена",
                    "{\"runId\":\"" + (runId == null ? "" : runId)
                            + "\",\"tasks\":" + tasks.size()
                            + ",\"changedFiles\":" + changedFiles
                            + ",\"directObjects\":" + directObjects
                            + ",\"savedObjects\":" + savedObjects + "}",
                    null,
                    "system",
                    runId
            );
        }
    }

    private GitlabChangesService.ChangedFile toChangedFile(TaskChangedFile row) {
        if (row == null || row.getChangeType() == null) {
            return null;
        }
        return new GitlabChangesService.ChangedFile(row.getChangeType(), row.getOldPath(), row.getNewPath());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
