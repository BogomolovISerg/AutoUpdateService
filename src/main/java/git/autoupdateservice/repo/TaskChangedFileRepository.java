package git.autoupdateservice.repo;

import git.autoupdateservice.domain.TaskChangedFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface TaskChangedFileRepository extends JpaRepository<TaskChangedFile, UUID> {
    @Transactional
    void deleteByTask_Id(UUID taskId);

    List<TaskChangedFile> findByTask_IdOrderByCreatedAtAsc(UUID taskId);
}
