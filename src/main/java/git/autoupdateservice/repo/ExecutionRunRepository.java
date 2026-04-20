package git.autoupdateservice.repo;

import git.autoupdateservice.domain.ExecutionRun;
import git.autoupdateservice.domain.RunStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, UUID> {
    Optional<ExecutionRun> findByPlannedFor(OffsetDateTime plannedFor);

    Optional<ExecutionRun> findByPlannedForAndStage(OffsetDateTime plannedFor, RunStage stage);

    Optional<ExecutionRun> findTopByPlannedForAndStageOrderByStartedAtDesc(OffsetDateTime plannedFor, RunStage stage);

    List<ExecutionRun> findTop20ByOrderByStartedAtDesc();
}
