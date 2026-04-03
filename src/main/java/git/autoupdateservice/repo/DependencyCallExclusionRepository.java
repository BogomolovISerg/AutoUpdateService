package git.autoupdateservice.repo;

import git.autoupdateservice.domain.DependencyCallExclusion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DependencyCallExclusionRepository extends JpaRepository<DependencyCallExclusion, UUID> {
    List<DependencyCallExclusion> findAllByEnabledIsTrueOrderByCallNameAsc();
}
