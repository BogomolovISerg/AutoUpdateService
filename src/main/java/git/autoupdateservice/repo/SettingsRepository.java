package git.autoupdateservice.repo;

import git.autoupdateservice.domain.Settings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public interface SettingsRepository extends JpaRepository<Settings, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Settings s set s.nextTestRunDate = :nextDate, s.updatedAt = :updatedAt where s.id = :id")
    int updateNextTestRunDate(@Param("id") Long id, @Param("nextDate") LocalDate nextDate, @Param("updatedAt") OffsetDateTime updatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Settings s set s.nextProductionRunDate = :nextDate, s.updatedAt = :updatedAt where s.id = :id")
    int updateNextProductionRunDate(@Param("id") Long id, @Param("nextDate") LocalDate nextDate, @Param("updatedAt") OffsetDateTime updatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Settings s set s.nextCleanupRunDate = :nextDate, s.updatedAt = :updatedAt where s.id = :id")
    int updateNextCleanupRunDate(@Param("id") Long id, @Param("nextDate") LocalDate nextDate, @Param("updatedAt") OffsetDateTime updatedAt);
}
