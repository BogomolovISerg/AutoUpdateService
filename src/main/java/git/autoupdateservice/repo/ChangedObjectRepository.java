package git.autoupdateservice.repo;

import git.autoupdateservice.domain.ChangedObject;
import git.autoupdateservice.domain.ChangedObjectStatus;
import git.autoupdateservice.domain.DependencyCallerType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChangedObjectRepository extends JpaRepository<ChangedObject, UUID> {

    Optional<ChangedObject> findFirstByBusinessDateAndObjectTypeAndObjectName(
            LocalDate businessDate,
            DependencyCallerType objectType,
            String objectName
    );

    List<ChangedObject> findByStatusIn(Collection<ChangedObjectStatus> statuses);
}
