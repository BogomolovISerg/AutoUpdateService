package git.autoupdateservice.service;

import git.autoupdateservice.domain.CommonModuleImpact;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CommonModuleImpactRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DependencyTreeSearchService {

    private final DependencySnapshotService dependencySnapshotService;
    private final DependencySearchMapper dependencySearchMapper;
    private final CommonModuleImpactRepository commonModuleImpactRepository;

    @Transactional(readOnly = true)
    public DependencySnapshot findSnapshotOrNull(UUID snapshotId) {
        return dependencySnapshotService.findSnapshotOrNull(snapshotId);
    }

    @Transactional(readOnly = true)
    public Optional<DependencySnapshot> latestSnapshot() {
        return dependencySnapshotService.latestReadySnapshot();
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
                dependencySearchMapper.normalizeLike(q),
                dependencySearchMapper.objectTypeName(objectType),
                limit,
                offset
        );

        return rows.stream()
                .map(dependencySearchMapper::toModuleNode)
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
                dependencySearchMapper.normalizeLike(q),
                dependencySearchMapper.objectTypeName(objectType)
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
                dependencySearchMapper.objectTypeName(objectType)
        );

        return rows.stream()
                .map(dependencySearchMapper::toMethodNode)
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
                dependencySearchMapper.objectTypeName(objectType)
        );

        return rows.stream()
                .map(dependencySearchMapper::toObjectNode)
                .filter(Objects::nonNull)
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

        DependencySnapshot snapshot = dependencySnapshotService.latestReadySnapshot().orElse(null);
        if (snapshot == null) {
            return List.of();
        }

        Set<String> normalizedNames = dependencySearchMapper.normalizeModuleNames(commonModuleNames);
        if (normalizedNames.isEmpty()) {
            return List.of();
        }

        List<CommonModuleImpact> impacts =
                commonModuleImpactRepository.findBySnapshotAndCommonModuleNameInIgnoreCase(snapshot, normalizedNames);

        return impacts.stream()
                .map(dependencySearchMapper::toAffectedObject)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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
