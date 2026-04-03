package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "update_task",
        indexes = {@Index(name = "ix_update_task_status_scheduled_for", columnList = "status,scheduled_for")},
        uniqueConstraints = {@UniqueConstraint(name = "uk_update_task_source_key", columnNames = "source_key")}
)
@Getter @Setter
public class UpdateTask {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_binding_id", nullable = false)
    private RepoBinding repoBinding;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private TargetType targetType;

    @Column(name = "extension_name", length = 200)
    private String extensionName;

    @Column(name = "repo_path", nullable = false, length = 1000)
    private String repoPath;

    @Column(name = "project_path", nullable = false, length = 300)
    private String projectPath;

    @Column(name = "branch", length = 200)
    private String branch;

    @Column(name = "commit_sha", length = 80)
    private String commitSha;

    @Column(name = "before_sha", length = 80)
    private String beforeSha;

    @Column(name = "author_name", length = 200)
    private String authorName;

    @Column(name = "author_login", length = 200)
    private String authorLogin;

    @Column(name = "comment", length = 4000)
    private String comment;

    @Column(name = "source_key", nullable = false, length = 700)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TaskStatus status = TaskStatus.NEW;

    @Column(name = "scheduled_for", nullable = false)
    private LocalDate scheduledFor;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
