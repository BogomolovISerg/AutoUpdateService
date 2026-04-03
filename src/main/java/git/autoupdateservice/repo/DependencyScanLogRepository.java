package git.autoupdateservice.repo;

import git.autoupdateservice.domain.DependencyScanLog;
import git.autoupdateservice.domain.DependencySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DependencyScanLogRepository extends JpaRepository<DependencyScanLog, UUID> {

    List<DependencyScanLog> findTop200BySnapshotOrderByCreatedAtDesc(DependencySnapshot snapshot);

    void deleteBySnapshot(DependencySnapshot snapshot);
}