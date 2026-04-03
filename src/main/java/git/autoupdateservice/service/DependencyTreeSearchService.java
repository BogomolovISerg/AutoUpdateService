package git.autoupdateservice.service;

import git.autoupdateservice.domain.CommonModuleImpact;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.DependencySnapshotStatus;
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

@Service
@RequiredArgsConstructor
public class DependencyTreeSearchService {

    private final DependencySnapshotRepository dependencySnapshotRepository;
    private final CommonModuleImpactRepository commonModuleImpactRepository;

    @Transactional(readOnly = true)
    public Optional<DependencySnapshot> latestSnapshot() {
        return dependencySnapshotRepository.findTopByStatusOrderByFinishedAtDesc(DependencySnapshotStatus.READY);
    }

    @Transactional(readOnly = true)
    public Optional<DependencySnapshot> getLatestReadySnapshot() {
        return latestSnapshot();
    }

    @Transactional(readOnly = true)
    public List<ModuleNode> findModules(String q, DependencyCallerType objectType, int limit, int offset) {
        DependencySnapshot snapshot = latestSnapshot().orElse(null);
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
                        .commonModuleName(r.getCommonModuleName())
                        .methodCount(r.getMethodCount())
                        .objectCount(r.getObjectCount())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MethodNode> findMethods(String moduleName, DependencyCallerType objectType) {
        DependencySnapshot snapshot = latestSnapshot().orElse(null);
        if (snapshot == null || isBlank(moduleName)) {
            return List.of();
        }

        List<CommonModuleImpactRepository.MethodAggRow> rows = commonModuleImpactRepository.findMethodNodes(
                snapshot.getId(),
                moduleName,
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
    public List<ObjectNode> findObjects(String moduleName, String methodName, DependencyCallerType objectType) {
        DependencySnapshot snapshot = latestSnapshot().orElse(null);
        if (snapshot == null || isBlank(moduleName) || isBlank(methodName)) {
            return List.of();
        }

        List<CommonModuleImpactRepository.ObjectRow> rows = commonModuleImpactRepository.findObjectNodes(
                snapshot.getId(),
                moduleName,
                methodName,
                objectType == null ? null : objectType.name()
        );

        return rows.stream()
                .filter(r -> !isBlank(r.getObjectType()))
                .filter(r -> !isBlank(r.getObjectName()))
                .map(r -> ObjectNode.builder()
                        .objectType(DependencyCallerType.valueOf(r.getObjectType()))
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Value
    @Builder
    public static class ModuleNode {
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
