package io.github.ricardoord.opswatch.incident;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone is looking into an open incident (docs/architecture/events.md). Published from OW-033; nobody listens in V1,
 * the real-time stream will (Phase 8).
 */
public record IncidentAcknowledged(
        UUID incidentId,
        UUID organizationId,
        UUID projectId,
        UUID monitorId,
        Instant acknowledgedAt,
        UUID acknowledgedBy) {}
