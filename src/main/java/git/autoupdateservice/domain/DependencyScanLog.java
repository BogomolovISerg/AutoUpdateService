package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "dependency_scan_log")
@Getter
@Setter
public class DependencyScanLog {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private DependencySnapshot snapshot;

    @Column(name = "level", nullable = false, length = 10)
    private String level;

    @Column(name = "phase", nullable = false, length = 50)
    private String phase;

    @Column(name = "source_path", length = 2000)
    private String sourcePath;

    @Column(name = "message", nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "stacktrace", columnDefinition = "text")
    private String stacktrace;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}