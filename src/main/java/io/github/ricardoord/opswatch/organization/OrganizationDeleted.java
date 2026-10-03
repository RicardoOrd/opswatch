package io.github.ricardoord.opswatch.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * An organization was deleted (logically), together with its projects, each of which publishes its
 * {@link ProjectDeleted} in the same transaction. Nobody listens to this one in V1. See docs/architecture/events.md.
 */
public record OrganizationDeleted(UUID organizationId, Instant occurredAt) {}
