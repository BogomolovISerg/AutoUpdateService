package git.autoupdateservice.repo;

import git.autoupdateservice.domain.CommonModuleImpact;
import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.SourceKind;
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

    @Query("""
           select c
           from CommonModuleImpact c
           where c.snapshot = :snapshot
             and c.sourceKind = :sourceKind
             and c.sourceName = :sourceName
             and lower(c.commonModuleName) = :moduleNameLower
           """)
    List<CommonModuleImpact> findBySnapshotAndSourceKindAndSourceNameAndCommonModuleNameIgnoreCase(
            @Param("snapshot") DependencySnapshot snapshot,
            @Param("sourceKind") SourceKind sourceKind,
            @Param("sourceName") String sourceName,
            @Param("moduleNameLower") String moduleNameLower
    );

    @Query(value = """
            with distinct_modules as (
                select distinct
                    coalesce(nullif(btrim(c.source_kind), ''), 'BASE') as sourceKind,
                    coalesce(nullif(btrim(c.source_name), ''), 'Основная конфигурация') as sourceName,
                    c.common_module_name as commonModuleName
                from common_module_impact c
                where c.snapshot_id = :snapshotId
                  and (:q = '' or lower(c.common_module_name) like :q)
                  and (:objectType = '' or c.object_type = :objectType)
            ),
            page_modules as (
                select *
                from distinct_modules
                order by commonModuleName,
                         case when sourceKind = 'BASE' then 0 else 1 end,
                         sourceName
                limit :limit offset :offset
            )
            select
                m.sourceKind as sourceKind,
                m.sourceName as sourceName,
                m.commonModuleName as commonModuleName,
                count(distinct c.common_module_member_name) as methodCount,
                count(distinct (coalesce(c.object_type, '') || '|' || coalesce(c.object_name, ''))) as objectCount
            from page_modules m
            left join common_module_impact c
              on c.snapshot_id = :snapshotId
             and coalesce(nullif(btrim(c.source_kind), ''), 'BASE') = m.sourceKind
             and coalesce(nullif(btrim(c.source_name), ''), 'Основная конфигурация') = m.sourceName
             and c.common_module_name = m.commonModuleName
             and (:objectType = '' or c.object_type = :objectType)
            group by m.sourceKind, m.sourceName, m.commonModuleName
            order by m.commonModuleName,
                     case when m.sourceKind = 'BASE' then 0 else 1 end,
                     m.sourceName
            """, nativeQuery = true)
    List<ModuleAggRow> findModuleNodes(
            @Param("snapshotId") UUID snapshotId,
            @Param("q") String q,
            @Param("objectType") String objectType,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Query(value = """
            select count(distinct (coalesce(nullif(btrim(c.source_kind), ''), 'BASE') || '|' || coalesce(nullif(btrim(c.source_name), ''), 'Основная конфигурация') || '|' || c.common_module_name))
            from common_module_impact c
            where c.snapshot_id = :snapshotId
              and (:q = '' or lower(c.common_module_name) like :q)
              and (:objectType = '' or c.object_type = :objectType)
            """, nativeQuery = true)
    long countModuleNodes(
            @Param("snapshotId") UUID snapshotId,
            @Param("q") String q,
            @Param("objectType") String objectType
    );

    @Query(value = """
            select
                c.common_module_member_name as commonModuleMemberName,
                count(distinct (coalesce(c.object_type, '') || '|' || coalesce(c.object_name, ''))) as objectCount
            from common_module_impact c
            where c.snapshot_id = :snapshotId
              and coalesce(nullif(btrim(c.source_kind), ''), 'BASE') = :sourceKind
              and coalesce(nullif(btrim(c.source_name), ''), 'Основная конфигурация') = :sourceName
              and c.common_module_name = :moduleName
              and c.common_module_member_name is not null
              and c.common_module_member_name <> ''
              and (:objectType = '' or c.object_type = :objectType)
            group by c.common_module_member_name
            order by c.common_module_member_name
            """, nativeQuery = true)
    List<MethodAggRow> findMethodNodes(
            @Param("snapshotId") UUID snapshotId,
            @Param("sourceKind") String sourceKind,
            @Param("sourceName") String sourceName,
            @Param("moduleName") String moduleName,
            @Param("objectType") String objectType
    );

    @Query(value = """
            select distinct
                c.object_type as objectType,
                c.object_name as objectName
            from common_module_impact c
            where c.snapshot_id = :snapshotId
              and coalesce(nullif(btrim(c.source_kind), ''), 'BASE') = :sourceKind
              and coalesce(nullif(btrim(c.source_name), ''), 'Основная конфигурация') = :sourceName
              and c.common_module_name = :moduleName
              and c.common_module_member_name = :methodName
              and (:objectType = '' or c.object_type = :objectType)
            order by c.object_type, c.object_name
            """, nativeQuery = true)
    List<ObjectRow> findObjectNodes(
            @Param("snapshotId") UUID snapshotId,
            @Param("sourceKind") String sourceKind,
            @Param("sourceName") String sourceName,
            @Param("moduleName") String moduleName,
            @Param("methodName") String methodName,
            @Param("objectType") String objectType
    );

    interface ModuleAggRow {
        String getSourceKind();
        String getSourceName();
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
