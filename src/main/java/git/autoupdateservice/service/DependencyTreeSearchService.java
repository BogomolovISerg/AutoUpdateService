package git.autoupdateservice.service;

import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.CodeSourceRootRepository;
import git.autoupdateservice.repo.CommonModuleImpactRepository;
import git.autoupdateservice.repo.DependencySnapshotRepository;
import git.autoupdateservice.web.model.DependencySearchRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DependencyTreeSearchService {

    private final CodeSourceRootRepository codeSourceRootRepository;
    private final DependencySnapshotRepository dependencySnapshotRepository;
    private final CommonModuleImpactRepository commonModuleImpactRepository;
    private final DependencyGraphStateService dependencyGraphStateService;

    public Optional<DependencySnapshot> latestReadySnapshot() {
        Optional<DependencySnapshot> active = dependencyGraphStateService.activeSnapshot();
        if (active.isPresent()) {
            return active;
        }
        return codeSourceRootRepository.findFirstBySourceKindAndEnabledIsTrue(SourceKind.BASE)
                .flatMap(src -> dependencySnapshotRepository.findTopBySourceRootAndStatusOrderByStartedAtDesc(src, DependencySnapshotStatus.READY));
    }

    public Optional<DependencySnapshot> latestSnapshot() {
        return codeSourceRootRepository.findFirstBySourceKindAndEnabledIsTrue(SourceKind.BASE)
                .flatMap(dependencySnapshotRepository::findTopBySourceRootOrderByStartedAtDesc);
    }

    public List<DependencySearchRow> findRows(String mode, String query, DependencyCallerType objectType) {
        Optional<DependencySnapshot> snapshotOpt = latestReadySnapshot();
        if (snapshotOpt.isEmpty()) return List.of();

        DependencySnapshot snapshot = snapshotOpt.get();
        List<CommonModuleImpact> rows;
        String q = query == null ? "" : query.trim();

        if (q.isBlank()) {
            if (objectType != null) {
                rows = commonModuleImpactRepository.findBySnapshotAndObjectTypeOrderByCommonModuleNameAscObjectNameAsc(snapshot, objectType);
                if (rows.size() > 100) rows = rows.subList(0, 100);
            } else {
                rows = commonModuleImpactRepository.findTop100BySnapshotOrderByCommonModuleNameAscObjectTypeAscObjectNameAsc(snapshot);
            }
        } else if ("object".equalsIgnoreCase(mode)) {
            rows = commonModuleImpactRepository.findBySnapshotAndObjectNameContainingIgnoreCaseOrderByObjectTypeAscObjectNameAscCommonModuleNameAsc(snapshot, q);
        } else {
            rows = commonModuleImpactRepository.findBySnapshotAndCommonModuleNameContainingIgnoreCaseOrderByCommonModuleNameAscObjectTypeAscObjectNameAsc(snapshot, q);
        }

        return rows.stream()
                .filter(r -> objectType == null || r.getObjectType() == objectType)
                .map(r -> DependencySearchRow.builder()
                        .commonModuleName(r.getCommonModuleName())
                        .commonModuleMemberName(r.getCommonModuleMemberName())
                        .objectType(r.getObjectType())
                        .objectName(r.getObjectName())
                        .viaModule(r.getViaModule())
                        .viaMember(r.getViaMember())
                        .sourcePath(r.getSourcePath())
                        .build())
                .toList();
    }
}
