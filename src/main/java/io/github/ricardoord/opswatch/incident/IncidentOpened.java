package io.github.ricardoord.opswatch.incident;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An incident was opened because its monitor went {@code DOWN} (docs/architecture/events.md). Published in the
 * transaction that records the check: {@code notification} learns of it only once that commits (OW-036). Ids, the name
 * of the monitor and what its check saw; never the URL or the headers.
 *
 * @param openedAt when the check that took the monitor down started
 * @param cause the failure reason of that check, as {@code monitoring} names it
 * @param httpStatus of that check; null if there was no response
 */
public record IncidentOpened(
        UUID incidentId,
        UUID organizationId,
        UUID projectId,
        UUID monitorId,
        String monitorName,
        Instant openedAt,
        String cause,
        @Nullable Integer httpStatus) {}
