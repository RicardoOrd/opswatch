package io.github.ricardoord.opswatch.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * A project was deleted (logically), alone or with its organization. {@code monitoring} deletes its monitors in
 * response, asynchronously and through the event registry, from OW-044. Ids only: the payload is stored in clear in
 * the registry. See docs/architecture/events.md.
 *
 * @param deletedBy who deleted the project, or its organization
 */
public record ProjectDeleted(UUID projectId, UUID organizationId, Instant occurredAt, UUID deletedBy) {}
