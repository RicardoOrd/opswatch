package io.github.ricardoord.opswatch.incident;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An incident as other modules see it: what the events tell of it, never who acknowledged it or its timeline.
 *
 * @param status {@code OPEN}, {@code ACKNOWLEDGED} or {@code RESOLVED}, as it is now
 * @param cause the failure reason of the check that opened it, as {@code monitoring} names it
 * @param httpStatus of that check; null if there was no response
 * @param resolvedAt null while it is active
 * @param resolution null while it is active
 */
public record IncidentSummary(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID monitorId,
        String monitorName,
        String status,
        String cause,
        @Nullable Integer httpStatus,
        Instant openedAt,
        @Nullable Instant resolvedAt,
        @Nullable Resolution resolution) {}
