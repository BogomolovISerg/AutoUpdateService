package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "step_log_blob",
        indexes = {
                @Index(name = "ix_step_log_blob_event", columnList = "event_id"),
                @Index(name = "ix_step_log_blob_run_step", columnList = "run_id,step_code")
        })
@Getter
@Setter
public class StepLogBlob {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "step_code", nullable = false, length = 200)
    private String stepCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private StepLogKind kind;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
