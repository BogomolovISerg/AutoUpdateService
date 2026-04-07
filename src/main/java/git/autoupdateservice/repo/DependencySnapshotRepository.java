package git.autoupdateservice.repo;

import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.DependencySnapshotStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DependencySnapshotRepository extends JpaRepository<DependencySnapshot, UUID> {

    Optional<DependencySnapshot> findTopByStatusOrderByFinishedAtDesc(DependencySnapshotStatus status);
    boolean existsByStatus(DependencySnapshotStatus status);
}