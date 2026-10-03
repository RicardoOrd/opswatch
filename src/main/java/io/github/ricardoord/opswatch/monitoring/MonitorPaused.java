package io.github.ricardoord.opswatch.monitoring;

import java.time.Instant;
import java.util.UUID;

/**
 * A monitor stopped being checked until someone resumes it (docs/architecture/events.md). Published in the transaction
 * of the pause: from OW-032, {@code incident} resolves its active incident there and then. Ids only: the payload of an
 * event may end up in the registry tables.
 */
public record MonitorPaused(UUID monitorId, UUID organizationId, UUID projectId, Instant occurredAt, UUID pausedBy) {}
