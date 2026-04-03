package git.autoupdateservice.repo;

import git.autoupdateservice.domain.RepoBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RepoBindingRepository extends JpaRepository<RepoBinding, UUID> {
    Optional<RepoBinding> findByProjectPathAndActiveTrue(String projectPath);
}
