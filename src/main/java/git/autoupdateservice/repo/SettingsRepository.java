package git.autoupdateservice.repo;

import git.autoupdateservice.domain.Settings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsRepository extends JpaRepository<Settings, Long> {}
