package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.Role;
import io.github.ricardoord.opswatch.organization.domain.OrganizationWithRole;
import java.time.Instant;
import java.util.UUID;

/**
 * An organization as the caller sees it. {@code version} is the value of its {@code ETag}, for {@code If-Match}.
 *
 * @param myRole the caller's role in it
 */
public record OrganizationResponse(UUID id, String name, Role myRole, Instant createdAt, long version) {

    static OrganizationResponse from(OrganizationWithRole view) {
        var organization = view.organization();
        return new OrganizationResponse(
                organization.id(),
                organization.name(),
                view.role(),
                organization.createdAt(),
                organization.savedVersion());
    }
}
