package git.autoupdateservice.repo;

import git.autoupdateservice.domain.DependencyEdge;
import git.autoupdateservice.domain.DependencySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DependencyEdgeRepository extends JpaRepository<DependencyEdge, UUID> {
    List<DependencyEdge> findBySnapshot(DependencySnapshot snapshot);
    void deleteBySnapshot(DependencySnapshot snapshot);
}
