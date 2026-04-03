package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "app_user", uniqueConstraints = {@UniqueConstraint(name = "uk_app_user_username", columnNames = "username")})
@Getter @Setter
public class AppUser {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "username", nullable = false, length = 200)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(name = "role", nullable = false, length = 50)
    private String role = "ADMIN";

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
