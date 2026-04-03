package git.autoupdateservice.repo;

import git.autoupdateservice.domain.CommonModuleImpact;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.DependencySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CommonModuleImpactRepository extends JpaRepository<CommonModuleImpact, UUID> {

    List<CommonModuleImpact> findTop100BySnapshotOrderByCommonModuleNameAscObjectTypeAscObjectNameAsc(
            DependencySnapshot snapshot
    );

    List<CommonModuleImpact> findBySnapshotAndCommonModuleNameContainingIgnoreCaseOrderByCommonModuleNameAscObjectTypeAscObjectNameAsc(
            DependencySnapshot snapshot,
            String q
    );

    List<CommonModuleImpact> findBySnapshotAndObjectNameContainingIgnoreCaseOrderByObjectTypeAscObjectNameAscCommonModuleNameAsc(
            DependencySnapshot snapshot,
            String q
    );

    List<CommonModuleImpact> findBySnapshotAndObjectTypeOrderByCommonModuleNameAscObjectNameAsc(
            DependencySnapshot snapshot,
            DependencyCallerType objectType
    );

    void deleteBySnapshot(DependencySnapshot snapshot);
}
