package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.monitoring.domain.Monitor;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorRepository;
import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Authorizes on a monitor loaded by its id, for every use case that takes one: on its project, which must not be
 * deleted either. A non-member gets the 404 of a missing monitor, never one that names its project or organization
 * (docs/security/authorization-model.md).
 */
@Component
class MonitorAccess {

    private final MonitorRepository monitors;
    private final AccessControl access;

    MonitorAccess(MonitorRepository monitors, AccessControl access) {
        this.monitors = monitors;
        this.access = access;
    }

    /** @throws ResourceNotFoundException if it is missing or deleted, or the user may not see it (404) */
    Monitor require(UUID userId, UUID monitorId, Permission permission) {
        return require(userId, monitorId, permission, monitors.findByIdAndDeletedAtIsNull(monitorId));
    }

    /** @param found as the caller loaded it, locked for instance; empty if missing or deleted */
    Monitor require(UUID userId, UUID monitorId, Permission permission, Optional<Monitor> found) {
        Monitor monitor = found.orElseThrow(() -> new ResourceNotFoundException("monitor", monitorId));
        try {
            access.requireForProject(userId, monitor.projectId(), permission);
        } catch (ResourceNotFoundException ex) {
            throw new ResourceNotFoundException("monitor", monitorId);
        }
        return monitor;
    }
}
