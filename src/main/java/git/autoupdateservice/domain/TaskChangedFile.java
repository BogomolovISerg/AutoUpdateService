package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "task_changed_file", indexes = {
        @Index(name = "ix_task_changed_file_task", columnList = "task_id"),
        @Index(name = "ix_task_changed_file_run", columnList = "run_id"),
        @Index(name = "ix_task_changed_file_task_run", columnList = "task_id,run_id")
})
@Getter
@Setter
public class TaskChangedFile {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private UpdateTask task;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "project_path", nullable = false, length = 300)
    private String projectPath;

    @Column(name = "from_sha", length = 80)
    private String fromSha;

    @Column(name = "to_sha", length = 80)
    private String toSha;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 30)
    private GitChangeType changeType;

    @Column(name = "old_path", length = 2000)
    private String oldPath;

    @Column(name = "new_path", length = 2000)
    private String newPath;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
