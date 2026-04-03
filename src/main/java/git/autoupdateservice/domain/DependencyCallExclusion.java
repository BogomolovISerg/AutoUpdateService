package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "dependency_call_exclusion")
@Getter
@Setter
public class DependencyCallExclusion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "call_name", nullable = false, length = 255)
    private String callName;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "notes")
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
