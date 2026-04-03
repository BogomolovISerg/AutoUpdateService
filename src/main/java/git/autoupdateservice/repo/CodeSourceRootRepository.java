package git.autoupdateservice.repo;

import git.autoupdateservice.domain.CodeSourceRoot;
import git.autoupdateservice.domain.SourceKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodeSourceRootRepository extends JpaRepository<CodeSourceRoot, UUID> {
    Optional<CodeSourceRoot> findFirstBySourceKindAndEnabledIsTrue(SourceKind sourceKind);
    Optional<CodeSourceRoot> findFirstBySourceKindOrderByUpdatedAtDesc(SourceKind sourceKind);
    List<CodeSourceRoot> findAllByEnabledIsTrueOrderByPriorityAscSourceNameAsc();
}
