package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "common_module_impact")
@Getter
@Setter
public class CommonModuleImpact {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private DependencySnapshot snapshot;

    @Column(name = "common_module_name", nullable = false, length = 255)
    private String commonModuleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 50)
    private DependencyCallerType objectType;

    @Column(name = "object_name", nullable = false, length = 255)
    private String objectName;

    @Column(name = "source_path", length = 2000)
    private String sourcePath;

    @Column(name = "via_module", length = 255)
    private String viaModule;

    @Column(name = "via_member", length = 255)
    private String viaMember;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
