package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "changed_object", uniqueConstraints = {
        @UniqueConstraint(name = "ux_changed_object_business_key", columnNames = {"business_date", "object_type", "object_name"})
}, indexes = {
        @Index(name = "ix_changed_object_status", columnList = "status"),
        @Index(name = "ix_changed_object_business_date", columnList = "business_date")
})
@Getter
@Setter
public class ChangedObject {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 50)
    private DependencyCallerType objectType;

    @Column(name = "object_name", nullable = false, length = 255)
    private String objectName;

    @Column(name = "project_path", length = 300)
    private String projectPath;

    @Column(name = "changed_path", length = 2000)
    private String changedPath;

    @Column(name = "direct_change_detected", nullable = false)
    private boolean directChangeDetected;

    @Column(name = "graph_impact_detected", nullable = false)
    private boolean graphImpactDetected;

    @Column(name = "first_detected_at", nullable = false)
    private OffsetDateTime firstDetectedAt = OffsetDateTime.now();

    @Column(name = "last_detected_at", nullable = false)
    private OffsetDateTime lastDetectedAt = OffsetDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChangedObjectStatus status = ChangedObjectStatus.NEW;
}
