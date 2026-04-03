package git.autoupdateservice.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface TaskChangedFileRepository extends JpaRepository<TaskChangedFile, UUID> {
    @Transactional
    void deleteByTask_Id(UUID taskId);
}
