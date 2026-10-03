package io.github.ricardoord.opswatch.organization;

import io.github.ricardoord.opswatch.shared.error.PermissionDeniedException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import java.util.UUID;

/**
 * The authorization decision of every use case (docs/security/authorization-model.md#4-cómo-se-decide-en-cada-petición).
 * The organization comes from the resource the use case already loaded, never from the request.
 */
public interface AccessControl {

    /**
     * Checks that the user holds the permission in the organization.
     *
     * @return the user's role there, for responses that show it
     * @throws ResourceNotFoundException if the user is not a member, or the organization does not exist or is deleted
     *     (404: whether it exists stays hidden)
     * @throws PermissionDeniedException if the user is a member but the role lacks the permission (403)
     */
    Role require(UUID userId, UUID organizationId, Permission permission);

    /**
     * Checks that the user holds the permission in the organization of the project.
     *
     * @return the project and its organization
     * @throws ResourceNotFoundException if the project does not exist or is deleted, or the user is not a member of its
     *     organization (404, about the project: neither its existence nor its organization shows)
     * @throws PermissionDeniedException if the user is a member but the role lacks the permission (403)
     */
    ProjectRef requireForProject(UUID userId, UUID projectId, Permission permission);
}
