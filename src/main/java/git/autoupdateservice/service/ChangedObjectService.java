package git.autoupdateservice.service;

import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.ChangedObjectRepository;
import git.autoupdateservice.repo.CommonModuleImpactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ChangedObjectService {

    private final ChangedObjectRepository changedObjectRepository;
    private final CommonModuleImpactRepository commonModuleImpactRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public int registerDirectObjects(
            UpdateTask task,
            Collection<DependencyGraphChangeDetector.DirectObjectHit> hits,
            String clientIp
    ) {
        if (task == null || task.getScheduledFor() == null || hits == null || hits.isEmpty()) {
            return 0;
        }

        int affected = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (DependencyGraphChangeDetector.DirectObjectHit hit : hits) {
            if (hit == null || hit.objectType() == null || isBlank(hit.objectName())) {
                continue;
            }
            ChangedObject row = changedObjectRepository
                    .findFirstByBusinessDateAndObjectTypeAndObjectName(task.getScheduledFor(), hit.objectType(), hit.objectName())
                    .orElseGet(ChangedObject::new);

            boolean fresh = row.getId() == null;
            if (fresh) {
                row.setBusinessDate(task.getScheduledFor());
                row.setObjectType(hit.objectType());
                row.setObjectName(hit.objectName());
                row.setFirstDetectedAt(now);
            }
            row.setProjectPath(task.getProjectPath());
            row.setChangedPath(hit.changedPath());
            row.setDirectChangeDetected(true);
            row.setLastDetectedAt(now);
            row.setStatus(ChangedObjectStatus.NEW);
            changedObjectRepository.save(row);
            affected++;
        }

        if (affected > 0) {
            auditLogService.info(
                    LogType.WEBHOOK_RECEIVED,
                    "Список напрямую измененных объектов обновлен",
                    "{\"taskId\":\"" + task.getId() + "\",\"projectPath\":\"" + esc(task.getProjectPath()) + "\",\"objects\":" + affected + "}",
                    clientIp,
                    "gitlab",
                    null
            );
        }
        return affected;
    }

    @Transactional
    public void registerObjectsFromDirtyModules(
            DependencySnapshot snapshot,
            Collection<DependencyGraphDirtyItem> dirtyItems,
            String clientIp
    ) {
        if (snapshot == null || dirtyItems == null || dirtyItems.isEmpty()) {
            return;
        }

        Set<DependencyGraphDirtyItem> uniqueItems = new LinkedHashSet<>(dirtyItems);
        int affectedObjects = 0;
        for (DependencyGraphDirtyItem dirtyItem : uniqueItems) {
            if (dirtyItem == null || dirtyItem.getSourceKind() == null || isBlank(dirtyItem.getSourceName()) || isBlank(dirtyItem.getModuleName())) {
                continue;
            }

            List<CommonModuleImpact> impacts = commonModuleImpactRepository
                    .findBySnapshotAndSourceKindAndSourceNameAndCommonModuleNameIgnoreCase(
                            snapshot,
                            dirtyItem.getSourceKind(),
                            dirtyItem.getSourceName().trim(),
                            dirtyItem.getModuleName().trim().toLowerCase()
                    );

            if (impacts.isEmpty()) {
                continue;
            }

            OffsetDateTime now = OffsetDateTime.now();
            Set<String> dedup = new LinkedHashSet<>();
            for (CommonModuleImpact impact : impacts) {
                if (impact == null || impact.getObjectType() == null || isBlank(impact.getObjectName())) {
                    continue;
                }
                String objectName = impact.getObjectName().trim();
                String dedupKey = impact.getObjectType().name() + "|" + objectName;
                if (!dedup.add(dedupKey)) {
                    continue;
                }

                ChangedObject row = changedObjectRepository
                        .findFirstByBusinessDateAndObjectTypeAndObjectName(
                                dirtyItem.getBusinessDate(),
                                impact.getObjectType(),
                                objectName
                        )
                        .orElseGet(ChangedObject::new);

                boolean fresh = row.getId() == null;
                if (fresh) {
                    row.setBusinessDate(dirtyItem.getBusinessDate());
                    row.setObjectType(impact.getObjectType());
                    row.setObjectName(objectName);
                    row.setFirstDetectedAt(now);
                }
                row.setProjectPath(firstNonBlank(row.getProjectPath(), dirtyItem.getSourceName()));
                row.setChangedPath(dirtyItem.getChangedPath());
                row.setGraphImpactDetected(true);
                row.setLastDetectedAt(now);
                row.setStatus(ChangedObjectStatus.NEW);
                changedObjectRepository.save(row);
                affectedObjects++;
            }
        }

        if (affectedObjects > 0) {
            auditLogService.info(
                    LogType.WEBHOOK_RECEIVED,
                    "Список объектов из графа зависимостей обновлен",
                    "{\"snapshotId\":\"" + snapshot.getId() + "\",\"objects\":" + affectedObjects + ",\"dirtyModules\":" + uniqueItems.size() + "}",
                    clientIp,
                    "system",
                    null
            );
        }
    }

    @Transactional(readOnly = true)
    public List<ChangedObject> findForTesting() {
        return changedObjectRepository.findByStatusIn(EnumSet.of(ChangedObjectStatus.NEW, ChangedObjectStatus.TEST_FAILED));
    }

    @Transactional(readOnly = true)
    public List<ChangedObject> findForProduction() {
        return changedObjectRepository.findByStatusIn(EnumSet.of(ChangedObjectStatus.TEST_OK));
    }

    @Transactional
    public void markTestingSucceeded() {
        changeStatus(EnumSet.of(ChangedObjectStatus.NEW, ChangedObjectStatus.TEST_FAILED), ChangedObjectStatus.TEST_OK);
    }

    @Transactional
    public void markTestingFailed() {
        changeStatus(EnumSet.of(ChangedObjectStatus.NEW, ChangedObjectStatus.TEST_FAILED), ChangedObjectStatus.TEST_FAILED);
    }

    @Transactional
    public void markProductionSucceeded() {
        changeStatus(EnumSet.of(ChangedObjectStatus.TEST_OK), ChangedObjectStatus.UPDATED);
    }

    private void changeStatus(Set<ChangedObjectStatus> fromStatuses, ChangedObjectStatus toStatus) {
        List<ChangedObject> rows = changedObjectRepository.findByStatusIn(fromStatuses);
        if (rows.isEmpty()) {
            return;
        }
        for (ChangedObject row : rows) {
            row.setStatus(toStatus);
        }
        changedObjectRepository.saveAll(rows);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
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
}
