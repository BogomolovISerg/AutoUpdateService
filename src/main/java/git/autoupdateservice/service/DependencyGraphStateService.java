package git.autoupdateservice.service;

import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.DependencyGraphDirtyItemRepository;
import git.autoupdateservice.repo.DependencyGraphStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DependencyGraphStateService {

    private final DependencyGraphStateRepository dependencyGraphStateRepository;
    private final DependencyGraphDirtyItemRepository dependencyGraphDirtyItemRepository;

    @Transactional
    public DependencyGraphState getState() {
        return dependencyGraphStateRepository.findById(1L).orElseGet(() -> {
            DependencyGraphState state = new DependencyGraphState();
            state.setId(1L);
            state.setUpdatedAt(OffsetDateTime.now());
            return dependencyGraphStateRepository.save(state);
        });
    }

    @Transactional(readOnly = true)
    public List<DependencyGraphDirtyItem> latestDirtyItems() {
        return dependencyGraphDirtyItemRepository.findTop50ByStatusOrderByDetectedAtDesc(DependencyGraphDirtyItemStatus.NEW);
    }

    @Transactional(readOnly = true)
    public Optional<DependencySnapshot> activeSnapshot() {
        return Optional.ofNullable(getState().getActiveSnapshot());
    }


    @Transactional
    public void markGitWebhookReceived(OffsetDateTime changeAt) {
        DependencyGraphState state = getState();
        OffsetDateTime now = changeAt != null ? changeAt : OffsetDateTime.now();
        state.setLastGitChangeAt(now);
        state.setUpdatedAt(OffsetDateTime.now());
        dependencyGraphStateRepository.save(state);
    }

    @Transactional
    public void markGraphStale(SourceKind sourceKind, String sourceName, Collection<DirtyModuleHit> hits, OffsetDateTime changeAt, String reason) {
        if (hits == null || hits.isEmpty()) {
            return;
        }

        DependencyGraphState state = getState();
        OffsetDateTime now = changeAt != null ? changeAt : OffsetDateTime.now();
        if (!state.isGraphIsStale()) {
            state.setGraphIsStale(true);
            state.setStaleSince(now);
        }
        state.setStaleReason(reason);
        state.setLastGitChangeAt(now);
        state.setUpdatedAt(OffsetDateTime.now());
        dependencyGraphStateRepository.save(state);

        String safeSourceName = (sourceName == null || sourceName.isBlank()) ? "" : sourceName.trim();
        for (DirtyModuleHit hit : hits) {
            if (hit == null || hit.moduleName() == null || hit.moduleName().isBlank() || hit.changedPath() == null || hit.changedPath().isBlank()) {
                continue;
            }
            boolean exists = dependencyGraphDirtyItemRepository
                    .findFirstByStatusAndSourceKindAndSourceNameAndModuleNameAndChangedPath(
                            DependencyGraphDirtyItemStatus.NEW,
                            sourceKind,
                            safeSourceName,
                            hit.moduleName(),
                            hit.changedPath())
                    .isPresent();
            if (exists) {
                continue;
            }

            DependencyGraphDirtyItem item = new DependencyGraphDirtyItem();
            item.setSourceKind(sourceKind);
            item.setSourceName(safeSourceName);
            item.setModuleName(hit.moduleName());
            item.setChangedPath(hit.changedPath());
            item.setDetectedAt(now);
            item.setStatus(DependencyGraphDirtyItemStatus.NEW);
            dependencyGraphDirtyItemRepository.save(item);
        }
    }

    @Transactional
    public void markSnapshotReady(DependencySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        DependencyGraphState state = getState();
        state.setActiveSnapshot(snapshot);
        state.setGraphIsStale(false);
        state.setStaleSince(null);
        state.setStaleReason(null);
        state.setLastRebuildAt(OffsetDateTime.now());
        state.setUpdatedAt(OffsetDateTime.now());
        dependencyGraphStateRepository.save(state);

        List<DependencyGraphDirtyItem> dirtyItems = dependencyGraphDirtyItemRepository.findByStatus(DependencyGraphDirtyItemStatus.NEW);
        OffsetDateTime now = OffsetDateTime.now();
        for (DependencyGraphDirtyItem item : dirtyItems) {
            item.setStatus(DependencyGraphDirtyItemStatus.PROCESSED);
            item.setDetectedAt(now);
        }
        if (!dirtyItems.isEmpty()) {
            dependencyGraphDirtyItemRepository.saveAll(dirtyItems);
        }
    }

    public record DirtyModuleHit(String moduleName, String changedPath) {
    }
}
