package git.autoupdateservice.repo;

import git.autoupdateservice.domain.ExecutionRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, UUID> {
    Optional<ExecutionRun> findByPlannedFor(OffsetDateTime plannedFor);
}
