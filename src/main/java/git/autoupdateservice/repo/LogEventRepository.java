package git.autoupdateservice.repo;

import git.autoupdateservice.domain.LogEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface LogEventRepository extends JpaRepository<LogEvent, UUID> {

    List<LogEvent> findTop500ByOrderByTsDesc();

    List<LogEvent> findTop500ByTsGreaterThanEqualOrderByTsDesc(OffsetDateTime from);

    List<LogEvent> findTop500ByTsLessThanEqualOrderByTsDesc(OffsetDateTime to);

    List<LogEvent> findTop500ByTsBetweenOrderByTsDesc(OffsetDateTime from, OffsetDateTime to);

    @Query("select e from LogEvent e " +
            "where e.ts >= coalesce(:from, e.ts) " +
            "and e.ts <= coalesce(:to, e.ts) " +
            "order by e.ts desc")
    Page<LogEvent> search(OffsetDateTime from, OffsetDateTime to, Pageable pageable);
}
