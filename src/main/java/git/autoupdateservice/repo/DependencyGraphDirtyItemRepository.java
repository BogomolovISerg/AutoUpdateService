package git.autoupdateservice.repo;

import git.autoupdateservice.domain.DependencyGraphDirtyItem;
import git.autoupdateservice.domain.DependencyGraphDirtyItemStatus;
import git.autoupdateservice.domain.SourceKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DependencyGraphDirtyItemRepository extends JpaRepository<DependencyGraphDirtyItem, UUID> {
    List<DependencyGraphDirtyItem> findTop50ByStatusOrderByDetectedAtDesc(DependencyGraphDirtyItemStatus status);
    List<DependencyGraphDirtyItem> findByStatus(DependencyGraphDirtyItemStatus status);
    Optional<DependencyGraphDirtyItem> findFirstByStatusAndSourceKindAndSourceNameAndModuleNameAndChangedPath(
            DependencyGraphDirtyItemStatus status,
            SourceKind sourceKind,
            String sourceName,
            String moduleName,
            String changedPath
    );

    Optional<DependencyGraphDirtyItem> findFirstByBusinessDateAndSourceKindAndSourceNameAndModuleName(
            LocalDate businessDate,
            SourceKind sourceKind,
            String sourceName,
            String moduleName
    );
}
