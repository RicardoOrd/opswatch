package io.github.ricardoord.opswatch.incident;

import java.time.Instant;
import java.util.UUID;

/**
 * An incident was resolved: its monitor recovered, or was paused or deleted (docs/architecture/events.md). Published in
 * the transaction that did it: {@code notification} learns of it only once that commits (OW-036).
 */
public record IncidentResolved(
        UUID incidentId,
        UUID organizationId,
        UUID projectId,
        UUID monitorId,
        String monitorName,
        Instant openedAt,
        Instant resolvedAt,
        Resolution resolution) {}
