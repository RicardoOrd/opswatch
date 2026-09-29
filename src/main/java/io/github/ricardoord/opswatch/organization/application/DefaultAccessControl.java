package io.github.ricardoord.opswatch.organization.application;

import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.organization.Role;
import io.github.ricardoord.opswatch.organization.domain.MembershipRepository;
import io.github.ricardoord.opswatch.shared.error.PermissionDeniedException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Looks the membership up on every call: roles are not in the token, so a revoked membership stops working at once
 * (docs/security/authorization-model.md).
 */
@Service
class DefaultAccessControl implements AccessControl {

    private static final Logger log = LoggerFactory.getLogger(DefaultAccessControl.class);

    private final MembershipRepository memberships;

    DefaultAccessControl(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Override
    @Transactional(readOnly = true)
    public Role require(UUID userId, UUID organizationId, Permission permission) {
        Role role = memberships
                .findActiveRole(organizationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("organization", organizationId));
        if (!role.grants(permission)) {
            log.atInfo()
                    .addKeyValue("event.category", "security")
                    .addKeyValue("event.action", "authz.denied")
                    .addKeyValue("user.id", userId)
                    .addKeyValue("organization.id", organizationId)
                    .addKeyValue("permission", permission)
                    .log("Permission {} denied to role {}", permission, role);
            throw new PermissionDeniedException(
                    "Your role in this organization does not include the " + permission + " permission.");
        }
        return role;
    }
}
