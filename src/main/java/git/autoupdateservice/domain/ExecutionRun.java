package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "execution_run", indexes = {@Index(name = "ix_execution_run_planned_for", columnList = "planned_for")})
@Getter @Setter
public class ExecutionRun {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "planned_for", nullable = false)
    private OffsetDateTime plannedFor;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RunStatus status = RunStatus.RUNNING;

    @Column(name = "error_summary", length = 4000)
    private String errorSummary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dependency_snapshot_id")
    private DependencySnapshot dependencySnapshot;
}
