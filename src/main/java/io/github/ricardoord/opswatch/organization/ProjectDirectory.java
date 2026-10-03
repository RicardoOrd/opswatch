package io.github.ricardoord.opswatch.organization;

import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import java.util.UUID;

/** Projects for the modules that hang things on them. Authorization is not here: it is {@link AccessControl}. */
public interface ProjectDirectory {

    /**
     * Locks the project ({@code SELECT … FOR SHARE}) until the caller's transaction ends, so that it cannot be deleted
     * meanwhile. Whatever the caller adds to it in that transaction exists before {@link ProjectDeleted} is published,
     * and its listeners see it (docs/architecture/domain-model.md#project).
     *
     * @throws ResourceNotFoundException if the project does not exist or is deleted (404)
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no transaction: the lock
     *     would end at once
     */
    ProjectRef lockActive(UUID projectId);
}
