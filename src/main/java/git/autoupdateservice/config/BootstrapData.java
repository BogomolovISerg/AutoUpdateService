package git.autoupdateservice.config;

import git.autoupdateservice.domain.AppUser;
import git.autoupdateservice.repo.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class BootstrapData implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        try {
            jdbcTemplate.execute("create extension if not exists pgcrypto");
        } catch (Exception ignored) {}

        if (appUserRepository.findByUsernameAndActiveTrue("admin").isEmpty()) {
            AppUser u = new AppUser();
            u.setUsername("admin");
            u.setPasswordHash(passwordEncoder.encode("admin"));
            u.setRole("ADMIN");
            u.setMustChangePassword(true);
            u.setActive(true);
            u.setCreatedAt(OffsetDateTime.now());
            u.setUpdatedAt(OffsetDateTime.now());
            appUserRepository.save(u);
        }
    }
}

