package git.autoupdateservice.repo;

import git.autoupdateservice.domain.TaskStatus;
import git.autoupdateservice.domain.UpdateTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UpdateTaskRepository extends JpaRepository<UpdateTask, UUID> {

    Optional<UpdateTask> findBySourceKey(String sourceKey);

    long countByStatus(TaskStatus status);

    List<UpdateTask> findTop200ByOrderByCreatedAtDesc();

    List<UpdateTask> findTop200ByStatusOrderByCreatedAtDesc(TaskStatus status);

    @Query("select t from UpdateTask t where t.status = :status order by t.createdAt asc")
    List<UpdateTask> findReadyToRun(TaskStatus status);

    List<UpdateTask> findByStatusInOrderByCreatedAtAsc(Collection<TaskStatus> statuses);

    @Query("select t from UpdateTask t " +
            "where t.scheduledFor >= coalesce(:from, t.scheduledFor) " +
            "and t.scheduledFor <= coalesce(:to, t.scheduledFor) " +
            "and t.status = coalesce(:status, t.status) " +
            "order by t.createdAt desc")
    List<UpdateTask> search(LocalDate from, LocalDate to, TaskStatus status);

    @Query("select t from UpdateTask t " +
            "where t.scheduledFor >= coalesce(:from, t.scheduledFor) " +
            "and t.scheduledFor <= coalesce(:to, t.scheduledFor) " +
            "and t.status = coalesce(:status, t.status) " +
            "order by t.createdAt desc")
    Page<UpdateTask> search(LocalDate from, LocalDate to, TaskStatus status, Pageable pageable);
}
