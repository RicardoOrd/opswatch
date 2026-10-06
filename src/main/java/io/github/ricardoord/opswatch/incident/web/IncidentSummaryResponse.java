package io.github.ricardoord.opswatch.incident.web;

import io.github.ricardoord.opswatch.incident.Resolution;
import io.github.ricardoord.opswatch.incident.domain.Incident;
import io.github.ricardoord.opswatch.incident.domain.IncidentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An incident in a listing, without its timeline.
 *
 * @param monitorName as the monitor was called when it went down
 * @param cause the failure reason of the check that took the monitor down
 * @param durationSeconds from {@code openedAt} to {@code resolvedAt}; null while it is active. The fall started up to
 *     {@code failureThreshold} checks before {@code openedAt}: confirming it costs those checks
 */
public record IncidentSummaryResponse(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID monitorId,
        String monitorName,
        IncidentStatus status,
        String cause,
        @Nullable Integer causeHttpStatus,
        Instant openedAt,
        @Nullable Instant acknowledgedAt,
        @Nullable Instant resolvedAt,
        @Nullable Resolution resolution,
        @Nullable Long durationSeconds,
        long version) {

    static IncidentSummaryResponse from(Incident incident) {
        return new IncidentSummaryResponse(
                incident.id(),
                incident.organizationId(),
                incident.projectId(),
                incident.monitorId(),
                incident.monitorName(),
                incident.status(),
                incident.cause(),
                incident.causeHttpStatus(),
                incident.openedAt(),
                incident.acknowledgedAt(),
                incident.resolvedAt(),
                incident.resolution(),
                durationSeconds(incident),
                version(incident));
    }

    static @Nullable Long durationSeconds(Incident incident) {
        Instant resolvedAt = incident.resolvedAt();
        return resolvedAt == null
                ? null
                : Duration.between(incident.openedAt(), resolvedAt).toSeconds();
    }

    static long version(Incident incident) {
        Long version = incident.savedVersion();
        return version == null ? 0 : version;
    }
}
