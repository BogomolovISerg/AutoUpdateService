package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "log_event",
        indexes = {
                @Index(name = "ix_log_event_ts", columnList = "ts"),
                @Index(name = "ix_log_event_type_ts", columnList = "type,ts")
        })
@Getter @Setter
public class LogEvent {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "ts", nullable = false)
    private OffsetDateTime ts = OffsetDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private LogType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 20)
    private LogLevel level = LogLevel.INFO;

    @Column(name = "message", nullable = false, length = 4000)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb")
    private String data;

    @Column(name = "client_ip", length = 80)
    private String clientIp;

    @Column(name = "actor", length = 200)
    private String actor;

    @Column(name = "run_id")
    private UUID runId;
}

