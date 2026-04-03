package git.autoupdateservice.repo;

import git.autoupdateservice.domain.StepLogBlob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StepLogBlobRepository extends JpaRepository<StepLogBlob, UUID> {
    List<StepLogBlob> findByEventIdOrderByKindAsc(UUID eventId);

    @Query("select distinct s.eventId from StepLogBlob s where s.eventId in :ids")
    List<UUID> findExistingEventIds(@Param("ids") List<UUID> ids);
}
