package io.github.ricardoord.opswatch.incident.web;

import io.github.ricardoord.opswatch.incident.Resolution;
import io.github.ricardoord.opswatch.incident.application.IncidentDetail;
import io.github.ricardoord.opswatch.incident.domain.Incident;
import io.github.ricardoord.opswatch.incident.domain.IncidentStatus;
import io.github.ricardoord.opswatch.incident.domain.TimelineEntryType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An incident with its timeline (docs/api/endpoints-v1.md#incidentes-incident).
 *
 * @param monitorName as the monitor was called when it went down
 * @param cause the failure reason of the check that took the monitor down
 * @param acknowledgedBy null if nobody acknowledged it, or that user's account is deleted
 * @param durationSeconds from {@code openedAt} to {@code resolvedAt}; null while it is active
 */
public record IncidentResponse(
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
        @Nullable ActorResponse acknowledgedBy,
        @Nullable Instant resolvedAt,
        @Nullable Resolution resolution,
        @Nullable Long durationSeconds,
        List<TimelineEntryResponse> timeline,
        long version) {

    static IncidentResponse from(IncidentDetail detail) {
        Incident incident = detail.incident();
        return new IncidentResponse(
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
                ActorResponse.from(detail.acknowledgedBy()),
                incident.resolvedAt(),
                incident.resolution(),
                IncidentSummaryResponse.durationSeconds(incident),
                detail.timeline().stream().map(TimelineEntryResponse::from).toList(),
                IncidentSummaryResponse.version(incident));
    }

    /** Someone, by the name the user has now. */
    public record ActorResponse(UUID id, String displayName) {

        static @Nullable ActorResponse from(IncidentDetail.@Nullable Actor actor) {
            return actor == null ? null : new ActorResponse(actor.id(), actor.displayName());
        }
    }

    /**
     * @param actor null for what the system did, and once that user's account is deleted
     * @param note only on an acknowledgement, and only if one was given
     */
    public record TimelineEntryResponse(
            TimelineEntryType type,
            Instant occurredAt,
            @Nullable ActorResponse actor,
            @Nullable String note) {

        static TimelineEntryResponse from(IncidentDetail.Entry entry) {
            return new TimelineEntryResponse(
                    entry.type(), entry.occurredAt(), ActorResponse.from(entry.actor()), entry.note());
        }
    }
}
