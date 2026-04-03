package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "dependency_snapshot")
@Getter
@Setter
public class DependencySnapshot {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_root_id", nullable = false)
    private CodeSourceRoot sourceRoot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DependencySnapshotStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "build_mode", nullable = false, length = 20)
    private DependencyBuildMode buildMode;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "files_scanned", nullable = false)
    private Integer filesScanned = 0;

    @Column(name = "notes")
    private String notes;
}
