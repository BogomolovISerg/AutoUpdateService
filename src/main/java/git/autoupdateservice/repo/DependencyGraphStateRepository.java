package git.autoupdateservice.repo;

import git.autoupdateservice.domain.DependencyGraphState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DependencyGraphStateRepository extends JpaRepository<DependencyGraphState, Long> {
}
