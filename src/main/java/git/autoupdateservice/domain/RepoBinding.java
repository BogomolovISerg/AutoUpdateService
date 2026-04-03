package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "repo_binding", uniqueConstraints = {
        @UniqueConstraint(name = "uk_repo_binding_project_path", columnNames = "project_path")
})
@Getter @Setter
public class RepoBinding {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_path", nullable = false, length = 300)
    private String projectPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private TargetType targetType;

    @Column(name = "extension_name", length = 200)
    private String extensionName;

    @Column(name = "repo_path", nullable = false, length = 1000)
    private String repoPath;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}

