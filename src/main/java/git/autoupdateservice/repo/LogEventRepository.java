package git.autoupdateservice.repo;

import git.autoupdateservice.domain.LogEvent;
import org.springframework.data.jpa.repository.JpaRepository;
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

    Page<LogEvent> findByTsGreaterThanEqual(OffsetDateTime from, Pageable pageable);

    Page<LogEvent> findByTsLessThanEqual(OffsetDateTime to, Pageable pageable);

    Page<LogEvent> findByTsBetween(OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    default Page<LogEvent> search(OffsetDateTime from, OffsetDateTime to, Pageable pageable) {
        if (from != null && to != null) {
            return findByTsBetween(from, to, pageable);
        }
        if (from != null) {
            return findByTsGreaterThanEqual(from, pageable);
        }
        if (to != null) {
            return findByTsLessThanEqual(to, pageable);
        }
        return findAll(pageable);
    }
}
