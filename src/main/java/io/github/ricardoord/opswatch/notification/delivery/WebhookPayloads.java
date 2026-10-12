package io.github.ricardoord.opswatch.notification.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.ricardoord.opswatch.incident.IncidentSummary;
import io.github.ricardoord.opswatch.incident.Resolution;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * The body of a webhook, version {@code 1} of a public contract (docs/api/webhooks.md): renaming or removing a field is
 * an incompatible change (docs/development/versioning.md#webhooks). Only what OpsWatch generates, never anything the
 * user wrote but the monitor name, and never the URL or the headers of the monitor.
 */
final class WebhookPayloads {

    /** Its own mapper: what is sent must not change with the settings of the web layer. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WebhookPayloads() {}

    static byte[] of(Notice notice) {
        IncidentSummary incident = notice.incident();
        Instant occurredAt = switch (notice.eventType()) {
            case INCIDENT_OPENED -> Objects.requireNonNull(incident).openedAt();
            case INCIDENT_RESOLVED ->
                Objects.requireNonNull(Objects.requireNonNull(incident).resolvedAt());
            case TEST -> notice.createdAt();
        };
        return JSON.writeValueAsBytes(new Body(
                notice.deliveryId(),
                notice.eventType().name(),
                occurredAt,
                incident == null ? null : IncidentPart.of(incident)));
    }

    /**
     * @param id of the delivery, the same on every attempt: a receiver discards a repeated delivery by it
     * @param incident null only in a {@code TEST}
     */
    record Body(
            UUID id,
            String type,
            Instant occurredAt,
            @Nullable IncidentPart incident) {}

    /** The incident as it is when the webhook is sent; the fields of a resolution only once it is resolved. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record IncidentPart(
            UUID id,
            String status,
            UUID monitorId,
            String monitorName,
            UUID projectId,
            String cause,
            @Nullable Integer httpStatus,
            Instant openedAt,
            @Nullable Instant resolvedAt,
            @Nullable Resolution resolution) {

        static IncidentPart of(IncidentSummary incident) {
            return new IncidentPart(
                    incident.id(),
                    incident.status(),
                    incident.monitorId(),
                    incident.monitorName(),
                    incident.projectId(),
                    incident.cause(),
                    incident.httpStatus(),
                    incident.openedAt(),
                    incident.resolvedAt(),
                    incident.resolution());
        }
    }
}
