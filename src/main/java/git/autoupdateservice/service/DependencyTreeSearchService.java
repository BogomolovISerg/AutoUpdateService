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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DependencyTreeSearchService {

    private final DependencySnapshotRepository dependencySnapshotRepository;
    private final CommonModuleImpactRepository commonModuleImpactRepository;

    @Transactional(readOnly = true)
    public Optional<DependencySnapshot> latestSnapshot() {
        return getLatestReadySnapshot();
    }

    @Transactional(readOnly = true)
    public Optional<DependencySnapshot> getLatestReadySnapshot() {
        return dependencySnapshotRepository.findTopByStatusOrderByFinishedAtDesc(DependencySnapshotStatus.READY);
    }

    @Transactional(readOnly = true)
    public List<DependencyRow> findRows(String mode, String q, DependencyCallerType objectType) {
        if (isBlank(q)) {
            return List.of();
        }

        String commonModuleName = q.trim();

        DependencySnapshot snapshot = getLatestReadySnapshot()
                .orElse(null);

        if (snapshot == null) {
            return List.of();
        }

        List<CommonModuleImpact> impacts =
                commonModuleImpactRepository.findBySnapshotAndCommonModuleNameInIgnoreCase(
                        snapshot,
                        Set.of(commonModuleName.toLowerCase(Locale.ROOT))
                );

        return impacts.stream()
                .filter(row -> row.getObjectType() != null)
                .filter(row -> !isBlank(row.getObjectName()))
                .filter(row -> objectType == null || row.getObjectType() == objectType)
                .map(row -> DependencyRow.builder()
                        .commonModuleName(row.getCommonModuleName())
                        .objectType(row.getObjectType())
                        .objectName(row.getObjectName())
                        .build())
                .distinct()
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

        DependencySnapshot snapshot = getLatestReadySnapshot()
                .orElse(null);

        if (snapshot == null) {
            return List.of();
        }

        Set<String> normalizedNames = commonModuleNames.stream()
                .filter(x -> x != null && !x.isBlank())
                .map(String::trim)
                .map(x -> x.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Value
    @Builder
    public static class DependencyRow {
        String commonModuleName;
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