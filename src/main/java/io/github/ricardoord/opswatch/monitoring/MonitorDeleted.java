package io.github.ricardoord.opswatch.monitoring;

import java.time.Instant;
import java.util.UUID;

/**
 * A monitor was deleted, by a member or because its project was (docs/architecture/events.md). Published in the
 * transaction of the deletion: from OW-032, {@code incident} resolves its active incident there and then. Ids only: the
 * payload of an event may end up in the registry tables.
 *
 * @param deletedBy who deleted the monitor or, when its project was deleted, who deleted the project
 */
public record MonitorDeleted(UUID monitorId, UUID organizationId, UUID projectId, Instant occurredAt, UUID deletedBy) {}
