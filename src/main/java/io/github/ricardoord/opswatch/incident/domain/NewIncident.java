package io.github.ricardoord.opswatch.incident.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An incident about to open: what the transition to {@code DOWN} of its monitor tells.
 *
 * @param monitorName as the monitor is called now: the incident keeps it even if the monitor is renamed
 * @param cause the failure reason of the check that took the monitor down
 * @param causeHttpStatus of that check; null if there was no response
 * @param openedAt when that check started, to the microsecond
 */
public record NewIncident(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID monitorId,
        String monitorName,
        String cause,
        @Nullable Integer causeHttpStatus,
        Instant openedAt) {}
