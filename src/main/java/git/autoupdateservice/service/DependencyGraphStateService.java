package git.autoupdateservice.service;

import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.DependencyGraphDirtyItemRepository;
import git.autoupdateservice.repo.DependencyGraphStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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

    @Transactional(readOnly = true)
    public List<DependencyGraphDirtyItem> pendingDirtyItems() {
        return dependencyGraphDirtyItemRepository.findByStatus(DependencyGraphDirtyItemStatus.NEW);
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
    public void markGraphStale(
            SourceKind sourceKind,
            String sourceName,
            Collection<DirtyModuleHit> hits,
            LocalDate businessDate,
            OffsetDateTime changeAt,
            String reason
    ) {
        markGraphStaleWithoutDirtyItems(changeAt, reason);

        if (hits == null || hits.isEmpty()) {
            return;
        }

        String safeSourceName = (sourceName == null || sourceName.isBlank()) ? "" : sourceName.trim();
        OffsetDateTime now = changeAt != null ? changeAt : OffsetDateTime.now();
        LocalDate safeBusinessDate = businessDate == null ? now.toLocalDate() : businessDate;
        saveDirtyItems(sourceKind, safeSourceName, hits, safeBusinessDate, now);
    }

    @Transactional
    public void markGraphStaleWithoutDirtyItems(SourceKind sourceKind, String sourceName, OffsetDateTime changeAt, String reason) {
        markGraphStaleWithoutDirtyItems(changeAt, reason);
    }

    private void markGraphStaleWithoutDirtyItems(OffsetDateTime changeAt, String reason) {
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
    }

    private void saveDirtyItems(
            SourceKind sourceKind,
            String safeSourceName,
            Collection<DirtyModuleHit> hits,
            LocalDate businessDate,
            OffsetDateTime now
    ) {
        for (DirtyModuleHit hit : hits) {
            if (hit == null || hit.moduleName() == null || hit.moduleName().isBlank() || hit.changedPath() == null || hit.changedPath().isBlank()) {
                continue;
            }
            DependencyGraphDirtyItem item = dependencyGraphDirtyItemRepository
                    .findFirstByBusinessDateAndSourceKindAndSourceNameAndModuleName(
                            businessDate,
                            sourceKind,
                            safeSourceName,
                            hit.moduleName())
                    .orElseGet(DependencyGraphDirtyItem::new);

            boolean fresh = item.getId() == null;
            if (fresh) {
                item.setBusinessDate(businessDate);
                item.setSourceKind(sourceKind);
                item.setSourceName(safeSourceName);
                item.setModuleName(hit.moduleName());
                item.setDetectedAt(now);
            }
            item.setChangedPath(hit.changedPath());
            item.setLastDetectedAt(now);
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
    }

    @Transactional
    public void markDirtyItemsProcessed(Collection<DependencyGraphDirtyItem> dirtyItems) {
        if (dirtyItems == null || dirtyItems.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (DependencyGraphDirtyItem item : dirtyItems) {
            if (item == null) {
                continue;
            }
            item.setStatus(DependencyGraphDirtyItemStatus.PROCESSED);
            item.setLastDetectedAt(now);
        }
        dependencyGraphDirtyItemRepository.saveAll(dirtyItems);
    }

    public record DirtyModuleHit(String moduleName, String changedPath) {
    }
}
