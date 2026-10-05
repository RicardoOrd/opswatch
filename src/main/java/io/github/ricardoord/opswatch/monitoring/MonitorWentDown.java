package io.github.ricardoord.opswatch.monitoring;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A monitor reached its failure threshold (docs/architecture/events.md). Published in the transaction that records the
 * check: from OW-032, {@code incident} opens the incident there and then, so a {@code DOWN} monitor never lacks one.
 * Ids, the name of the monitor and what the check saw; no personal data.
 *
 * @param occurredAt when the check that crossed the threshold started
 * @param cause of that check
 * @param httpStatus of that check; null if there was no response
 */
public record MonitorWentDown(
        UUID monitorId,
        UUID organizationId,
        UUID projectId,
        String monitorName,
        Instant occurredAt,
        FailureReason cause,
        @Nullable Integer httpStatus,
        int consecutiveFailures) {}
