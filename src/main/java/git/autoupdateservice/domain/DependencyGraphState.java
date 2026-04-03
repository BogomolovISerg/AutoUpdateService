package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "dependency_graph_state")
@Getter
@Setter
public class DependencyGraphState {

    @Id
    private Long id = 1L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_snapshot_id")
    private DependencySnapshot activeSnapshot;

    @Column(name = "graph_is_stale", nullable = false)
    private boolean graphIsStale = false;

    @Column(name = "stale_since")
    private OffsetDateTime staleSince;

    @Column(name = "stale_reason")
    private String staleReason;

    @Column(name = "last_git_change_at")
    private OffsetDateTime lastGitChangeAt;

    @Column(name = "last_rebuild_at")
    private OffsetDateTime lastRebuildAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
