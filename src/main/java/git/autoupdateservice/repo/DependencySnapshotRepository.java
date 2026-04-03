package git.autoupdateservice.repo;

import git.autoupdateservice.domain.CodeSourceRoot;
import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.DependencySnapshotStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DependencySnapshotRepository extends JpaRepository<DependencySnapshot, UUID> {
    Optional<DependencySnapshot> findTopBySourceRootAndStatusOrderByStartedAtDesc(
            CodeSourceRoot sourceRoot,
            DependencySnapshotStatus status
    );

    Optional<DependencySnapshot> findTopBySourceRootOrderByStartedAtDesc(CodeSourceRoot sourceRoot);
}
