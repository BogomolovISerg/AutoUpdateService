package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "dependency_edge")
@Getter
@Setter
public class DependencyEdge {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private DependencySnapshot snapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "caller_type", nullable = false, length = 50)
    private DependencyCallerType callerType;

    @Column(name = "caller_name", nullable = false, length = 255)
    private String callerName;

    @Column(name = "caller_member", length = 255)
    private String callerMember;

    @Column(name = "callee_module", nullable = false, length = 255)
    private String calleeModule;

    @Column(name = "callee_member", length = 255)
    private String calleeMember;

    @Column(name = "source_path", nullable = false, length = 2000)
    private String sourcePath;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
