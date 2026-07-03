package git.autoupdateservice.repo;

import git.autoupdateservice.domain.ExecutionRun;
import git.autoupdateservice.domain.RunStage;
import git.autoupdateservice.domain.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, UUID> {
    java.util.List<ExecutionRun> findTop20ByOrderByStartedAtDesc();

    Optional<ExecutionRun> findByPlannedFor(OffsetDateTime plannedFor);

    Optional<ExecutionRun> findTopByPlannedForAndStageOrderByStartedAtDesc(OffsetDateTime plannedFor, RunStage stage);
    long deleteByStatusInAndFinishedAtBefore(Collection<RunStatus> statuses, OffsetDateTime cutoff);
}
