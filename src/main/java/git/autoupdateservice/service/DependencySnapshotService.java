package git.autoupdateservice.service;

import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.DependencySnapshotStatus;
import git.autoupdateservice.repo.DependencySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DependencySnapshotService {

    private final DependencySnapshotRepository dependencySnapshotRepository;

    @Transactional(readOnly = true)
    public DependencySnapshot findSnapshotOrNull(UUID snapshotId) {
        if (snapshotId == null) {
            return null;
        }
        return dependencySnapshotRepository.findById(snapshotId).orElse(null);
    }

    @Transactional(readOnly = true)
    public Optional<DependencySnapshot> latestReadySnapshot() {
        return dependencySnapshotRepository.findTopByStatusOrderByFinishedAtDesc(DependencySnapshotStatus.READY);
    }
}
