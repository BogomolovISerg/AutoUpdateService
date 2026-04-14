package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "dependency_graph_dirty_item")
@Getter
@Setter
public class DependencyGraphDirtyItem {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 20)
    private SourceKind sourceKind;

    @Column(name = "source_name", nullable = false, length = 200)
    private String sourceName;

    @Column(name = "module_name", nullable = false, length = 255)
    private String moduleName;

    @Column(name = "changed_path", nullable = false, length = 2000)
    private String changedPath;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate = LocalDate.now();

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt = OffsetDateTime.now();

    @Column(name = "last_detected_at", nullable = false)
    private OffsetDateTime lastDetectedAt = OffsetDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DependencyGraphDirtyItemStatus status = DependencyGraphDirtyItemStatus.NEW;
}
