package git.autoupdateservice.repo;

import git.autoupdateservice.domain.CommonModuleImpact;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.DependencySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommonModuleImpactRepository extends JpaRepository<CommonModuleImpact, UUID> {

    void deleteBySnapshot(DependencySnapshot snapshot);

    @Query("""
           select c
           from CommonModuleImpact c
           where c.snapshot = :snapshot
             and lower(c.commonModuleName) in :moduleNamesLower
           """)
    List<CommonModuleImpact> findBySnapshotAndCommonModuleNameInIgnoreCase(
            @Param("snapshot") DependencySnapshot snapshot,
            @Param("moduleNamesLower") Collection<String> moduleNamesLower
    );

    List<CommonModuleImpact> findTop100BySnapshotOrderByCommonModuleNameAscObjectTypeAscObjectNameAsc(
            DependencySnapshot snapshot
    );

    List<CommonModuleImpact> findBySnapshotAndObjectTypeOrderByCommonModuleNameAscObjectNameAsc(
            DependencySnapshot snapshot,
            DependencyCallerType objectType
    );

    List<CommonModuleImpact> findBySnapshotAndCommonModuleNameContainingIgnoreCaseOrderByCommonModuleNameAscObjectTypeAscObjectNameAsc(
            DependencySnapshot snapshot,
            String commonModuleName
    );
}