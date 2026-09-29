package io.github.ricardoord.opswatch.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * An organization was deleted (logically). Its projects are deleted in response once they exist (OW-019); nobody else
 * listens in V1. See docs/architecture/events.md.
 */
public record OrganizationDeleted(UUID organizationId, Instant occurredAt) {}
