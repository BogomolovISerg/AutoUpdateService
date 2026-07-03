package git.autoupdateservice.service;

import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.ChangedObjectRepository;
import git.autoupdateservice.repo.CommonModuleImpactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChangedObjectService {

    private final ChangedObjectRepository changedObjectRepository;
    private final CommonModuleImpactRepository commonModuleImpactRepository;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbcTemplate;

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
            String objectName = hit.objectName().trim();
            if (isExcludedFromTesting(objectName)) {
                continue;
            }
            upsertChangedObject(
                    task.getScheduledFor(),
                    hit.objectType(),
                    objectName,
                    task.getProjectPath(),
                    hit.changedPath(),
                    true,
                    false,
                    now
            );
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
    public int registerObjectsFromDirtyModules(
            DependencySnapshot snapshot,
            Collection<DependencyGraphDirtyItem> dirtyItems,
            String clientIp
    ) {
        if (snapshot == null || dirtyItems == null || dirtyItems.isEmpty()) {
            return 0;
        }

        Set<DependencyGraphDirtyItem> uniqueItems = new LinkedHashSet<>(dirtyItems);
        Set<String> processedObjects = new LinkedHashSet<>();
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
                if (isExcludedFromTesting(objectName)) {
                    continue;
                }
                String dedupKey = impact.getObjectType().name() + "|" + objectName;
                String batchKey = dirtyItem.getBusinessDate() + "|" + dedupKey.toLowerCase(Locale.ROOT);
                if (!dedup.add(dedupKey) || !processedObjects.add(batchKey)) {
                    continue;
                }
                upsertChangedObject(
                        dirtyItem.getBusinessDate(),
                        impact.getObjectType(),
                        objectName,
                        dirtyItem.getSourceName(),
                        dirtyItem.getChangedPath(),
                        false,
                        true,
                        now
                );
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
        return affectedObjects;
    }

    private void upsertChangedObject(
            LocalDate businessDate,
            DependencyCallerType objectType,
            String objectName,
            String projectPath,
            String changedPath,
            boolean directChangeDetected,
            boolean graphImpactDetected,
            OffsetDateTime detectedAt
    ) {
        jdbcTemplate.update(
                """
                insert into public.changed_object (
                    id,
                    business_date,
                    object_type,
                    object_name,
                    project_path,
                    changed_path,
                    direct_change_detected,
                    graph_impact_detected,
                    first_detected_at,
                    last_detected_at,
                    status
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (business_date, object_type, object_name)
                do update set
                    project_path = case
                        when excluded.direct_change_detected then excluded.project_path
                        when changed_object.project_path is null or btrim(changed_object.project_path) = '' then excluded.project_path
                        else changed_object.project_path
                    end,
                    changed_path = excluded.changed_path,
                    direct_change_detected = changed_object.direct_change_detected or excluded.direct_change_detected,
                    graph_impact_detected = changed_object.graph_impact_detected or excluded.graph_impact_detected,
                    last_detected_at = excluded.last_detected_at,
                    status = excluded.status
                """,
                UUID.randomUUID(),
                businessDate,
                objectType.name(),
                objectName,
                trimToNull(projectPath),
                trimToNull(changedPath),
                directChangeDetected,
                graphImpactDetected,
                detectedAt,
                detectedAt,
                ChangedObjectStatus.NEW.name()
        );
    }

    @Transactional(readOnly = true)
    public List<ChangedObject> findForTesting() {
        return changedObjectRepository.findByStatusIn(EnumSet.of(ChangedObjectStatus.NEW, ChangedObjectStatus.TEST_FAILED))
                .stream()
                .filter(row -> row != null && !isExcludedFromTesting(row.getObjectName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChangedObject> findForProduction() {
        return changedObjectRepository.findByStatusIn(EnumSet.of(ChangedObjectStatus.TEST_OK))
                .stream()
                .filter(row -> row != null && !isExcludedFromTesting(row.getObjectName()))
                .toList();
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

    private static boolean isExcludedFromTesting(String objectName) {
        if (isBlank(objectName)) {
            return false;
        }
        String normalized = objectName.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("уат") || normalized.startsWith("uat");
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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
