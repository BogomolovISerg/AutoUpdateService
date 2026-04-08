package git.autoupdateservice.service;

import git.autoupdateservice.domain.CommonModuleImpact;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.DependencySnapshotStatus;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CommonModuleImpactRepository;
import git.autoupdateservice.repo.DependencySnapshotRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DependencyTreeSearchService {

    private final DependencySnapshotRepository dependencySnapshotRepository;
    private final CommonModuleImpactRepository commonModuleImpactRepository;

    @Transactional(readOnly = true)
    public DependencySnapshot findSnapshotOrNull(UUID snapshotId) {
        if (snapshotId == null) {
            return null;
        }
        return dependencySnapshotRepository.findById(snapshotId).orElse(null);
    }

    @Transactional(readOnly = true)
    public Optional<DependencySnapshot> latestSnapshot() {
        return dependencySnapshotRepository.findTopByStatusOrderByFinishedAtDesc(DependencySnapshotStatus.READY);
    }

    @Transactional(readOnly = true)
    public Optional<DependencySnapshot> getLatestReadySnapshot() {
        return latestSnapshot();
    }

    @Transactional(readOnly = true)
    public List<ModuleNode> findModules(UUID snapshotId, String q, DependencyCallerType objectType, int limit, int offset) {
                DependencySnapshot snapshot = findSnapshotOrNull(snapshotId);
        if (snapshot == null) {
            return List.of();
        }

        List<CommonModuleImpactRepository.ModuleAggRow> rows = commonModuleImpactRepository.findModuleNodes(
                snapshot.getId(),
                normalizeLike(q),
                objectType == null ? null : objectType.name(),
                limit,
                offset
        );

        return rows.stream()
                .map(r -> ModuleNode.builder()
                        .sourceKind(parseSourceKind(r.getSourceKind()))
                        .sourceName(defaultSourceName(parseSourceKind(r.getSourceKind()), r.getSourceName()))
                        .commonModuleName(r.getCommonModuleName())
                        .methodCount(r.getMethodCount())
                        .objectCount(r.getObjectCount())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public long countModules(UUID snapshotId, String q, DependencyCallerType objectType) {
                DependencySnapshot snapshot = findSnapshotOrNull(snapshotId);
        if (snapshot == null) {
            return 0;
        }

        return commonModuleImpactRepository.countModuleNodes(
                snapshot.getId(),
                normalizeLike(q),
                objectType == null ? null : objectType.name()
        );
    }

    @Transactional(readOnly = true)
    public List<MethodNode> findMethods(UUID snapshotId, String moduleName, SourceKind sourceKind, String sourceName, DependencyCallerType objectType) {
                DependencySnapshot snapshot = findSnapshotOrNull(snapshotId);
        if (snapshot == null || isBlank(moduleName) || sourceKind == null || isBlank(sourceName)) {
            return List.of();
        }

        List<CommonModuleImpactRepository.MethodAggRow> rows = commonModuleImpactRepository.findMethodNodes(
                snapshot.getId(),
                sourceKind.name(),
                sourceName.trim(),
                moduleName.trim(),
                objectType == null ? null : objectType.name()
        );

        return rows.stream()
                .map(r -> MethodNode.builder()
                        .methodName(r.getCommonModuleMemberName())
                        .objectCount(r.getObjectCount())
                        .build())
                .sorted(Comparator.comparing(MethodNode::getMethodName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ObjectNode> findObjects(
            UUID snapshotId,
            String moduleName,
            String methodName,
            SourceKind sourceKind,
            String sourceName,
            DependencyCallerType objectType
    ) {
        DependencySnapshot snapshot = findSnapshotOrNull(snapshotId);
        if (snapshot == null
                || isBlank(moduleName)
                || isBlank(methodName)
                || sourceKind == null
                || isBlank(sourceName)) {
            return List.of();
        }

        List<CommonModuleImpactRepository.ObjectRow> rows = commonModuleImpactRepository.findObjectNodes(
                snapshot.getId(),
                sourceKind.name(),
                sourceName.trim(),
                moduleName.trim(),
                methodName.trim(),
                objectType == null ? null : objectType.name()
        );

        return rows.stream()
                .filter(r -> !isBlank(r.getObjectType()))
                .filter(r -> !isBlank(r.getObjectName()))
                .map(r -> ObjectNode.builder()
                        .objectType(parseObjectType(r.getObjectType()))
                        .objectName(r.getObjectName())
                        .build())
                .sorted(Comparator
                        .comparing((ObjectNode x) -> x.getObjectType().name())
                        .thenComparing(ObjectNode::getObjectName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AffectedObject> findAffectedObjectsByCommonModule(String commonModuleName) {
        if (isBlank(commonModuleName)) {
            return List.of();
        }
        return findAffectedObjectsByCommonModules(Set.of(commonModuleName));
    }

    private DependencyCallerType parseObjectType(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return DependencyCallerType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    @Transactional(readOnly = true)
    public List<AffectedObject> findAffectedObjectsByCommonModules(Collection<String> commonModuleNames) {
        if (commonModuleNames == null || commonModuleNames.isEmpty()) {
            return List.of();
        }

        DependencySnapshot snapshot = latestSnapshot().orElse(null);
        if (snapshot == null) {
            return List.of();
        }

        Set<String> normalizedNames = commonModuleNames.stream()
                .filter(x -> x != null && !x.isBlank())
                .map(String::trim)
                .map(x -> x.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (normalizedNames.isEmpty()) {
            return List.of();
        }

        List<CommonModuleImpact> impacts =
                commonModuleImpactRepository.findBySnapshotAndCommonModuleNameInIgnoreCase(snapshot, normalizedNames);

        return impacts.stream()
                .filter(row -> row.getObjectType() != null)
                .filter(row -> !isBlank(row.getObjectName()))
                .map(row -> AffectedObject.builder()
                        .objectType(row.getObjectType())
                        .objectName(row.getObjectName())
                        .build())
                .distinct()
                .toList();
    }

    private String normalizeLike(String q) {
        if (isBlank(q)) {
            return null;
        }
        return "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private SourceKind parseSourceKind(String value) {
        if (isBlank(value)) {
            return SourceKind.BASE;
        }
        try {
            return SourceKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SourceKind.BASE;
        }
    }

    private String defaultSourceName(SourceKind sourceKind, String sourceName) {
        if (!isBlank(sourceName)) {
            return sourceName.trim();
        }
        return sourceKind == SourceKind.EXTENSION ? "Расширение" : "Основная конфигурация";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Value
    @Builder
    public static class ModuleNode {
        SourceKind sourceKind;
        String sourceName;
        String commonModuleName;
        Integer methodCount;
        Integer objectCount;
    }

    @Value
    @Builder
    public static class MethodNode {
        String methodName;
        Integer objectCount;
    }

    @Value
    @Builder
    public static class ObjectNode {
        DependencyCallerType objectType;
        String objectName;
    }

    @Value
    @Builder
    public static class AffectedObject {
        DependencyCallerType objectType;
        String objectName;
    }
}
