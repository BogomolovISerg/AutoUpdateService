package git.autoupdateservice.repo;

import git.autoupdateservice.domain.CommonModuleImpact;
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

    @Query(value = """
            select
                c.common_module_name as commonModuleName,
                count(distinct c.common_module_member_name) as methodCount,
                count(distinct (coalesce(c.object_type, '') || '|' || coalesce(c.object_name, ''))) as objectCount
            from common_module_impact c
            where c.snapshot_id = :snapshotId
              and (:q is null or lower(c.common_module_name) like :q)
              and (:objectType is null or c.object_type = :objectType)
            group by c.common_module_name
            order by c.common_module_name
            limit :limit offset :offset
            """, nativeQuery = true)
    List<ModuleAggRow> findModuleNodes(
            @Param("snapshotId") UUID snapshotId,
            @Param("q") String q,
            @Param("objectType") String objectType,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Query(value = """
            select
                c.common_module_member_name as commonModuleMemberName,
                count(distinct (coalesce(c.object_type, '') || '|' || coalesce(c.object_name, ''))) as objectCount
            from common_module_impact c
            where c.snapshot_id = :snapshotId
              and c.common_module_name = :moduleName
              and c.common_module_member_name is not null
              and c.common_module_member_name <> ''
              and (:objectType is null or c.object_type = :objectType)
            group by c.common_module_member_name
            order by c.common_module_member_name
            """, nativeQuery = true)
    List<MethodAggRow> findMethodNodes(
            @Param("snapshotId") UUID snapshotId,
            @Param("moduleName") String moduleName,
            @Param("objectType") String objectType
    );

    @Query(value = """
            select distinct
                c.object_type as objectType,
                c.object_name as objectName
            from common_module_impact c
            where c.snapshot_id = :snapshotId
              and c.common_module_name = :moduleName
              and c.common_module_member_name = :methodName
              and (:objectType is null or c.object_type = :objectType)
            order by c.object_type, c.object_name
            """, nativeQuery = true)
    List<ObjectRow> findObjectNodes(
            @Param("snapshotId") UUID snapshotId,
            @Param("moduleName") String moduleName,
            @Param("methodName") String methodName,
            @Param("objectType") String objectType
    );

    interface ModuleAggRow {
        String getCommonModuleName();
        Integer getMethodCount();
        Integer getObjectCount();
    }

    interface MethodAggRow {
        String getCommonModuleMemberName();
        Integer getObjectCount();
    }

    interface ObjectRow {
        String getObjectType();
        String getObjectName();
    }
}
