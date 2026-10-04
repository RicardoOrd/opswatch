package io.github.ricardoord.opswatch.monitoring;

import java.time.Instant;
import java.util.UUID;

/**
 * A {@code DOWN} monitor reached its recovery threshold (docs/architecture/events.md). Published in the transaction that
 * records the check: from OW-032, {@code incident} resolves the incident there and then.
 *
 * @param occurredAt when the check that crossed the threshold started
 * @param downSince when the monitor went {@code DOWN}
 */
public record MonitorRecovered(
        UUID monitorId,
        UUID organizationId,
        UUID projectId,
        String monitorName,
        Instant occurredAt,
        Instant downSince) {}
