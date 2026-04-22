package git.autoupdateservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "app_settings")
@Getter @Setter
public class Settings {
    @Id
    private Long id = 1L;

    @Column(name = "auto_update_enabled", nullable = false)
    private boolean autoUpdateEnabled = true;

    @Column(name = "dependency_graph_rebuild_enabled", nullable = false)
    private boolean dependencyGraphRebuildEnabled = true;

    @Column(name = "test_run_time", nullable = false)
    private LocalTime testRunTime = LocalTime.of(2, 0);

    @Column(name = "next_test_run_date")
    private LocalDate nextTestRunDate;

    @Column(name = "production_run_time", nullable = false)
    private LocalTime productionRunTime = LocalTime.of(4, 0);

    @Column(name = "next_production_run_date")
    private LocalDate nextProductionRunDate;

    @Column(name = "timezone", nullable = false, length = 60)
    private String timezone = "Europe/Zurich";

    @Column(name = "lock_message", nullable = false, length = 500)
    private String lockMessage = "Выполняется ночное обновление базы. Попробуйте позже.";

    @Column(name = "uccode", nullable = false, length = 100)
    private String uccode = "AUTO_UPDATE_1C";

    @Column(name = "closed_max_attempts", nullable = false)
    private int closedMaxAttempts = 12;

    @Column(name = "closed_sleep_seconds", nullable = false)
    private int closedSleepSeconds = 15;

    @Column(name = "queue_page_size", nullable = false)
    private int queuePageSize = 50;

    @Column(name = "logs_page_size", nullable = false)
    private int logsPageSize = 50;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}

