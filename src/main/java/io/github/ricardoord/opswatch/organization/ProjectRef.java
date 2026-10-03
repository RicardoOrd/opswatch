package io.github.ricardoord.opswatch.organization;

import java.util.UUID;

/**
 * A project not deleted and the organization it belongs to, as other modules see it. They take both from here, never
 * from the request.
 */
public record ProjectRef(UUID id, UUID organizationId) {}
