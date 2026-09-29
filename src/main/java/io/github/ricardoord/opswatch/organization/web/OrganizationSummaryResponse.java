package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.Role;
import io.github.ricardoord.opswatch.organization.domain.OrganizationWithRole;
import java.time.Instant;
import java.util.UUID;

/**
 * An organization in the caller's list.
 *
 * @param myRole the caller's role in it
 */
public record OrganizationSummaryResponse(UUID id, String name, Role myRole, Instant createdAt) {

    static OrganizationSummaryResponse from(OrganizationWithRole view) {
        var organization = view.organization();
        return new OrganizationSummaryResponse(
                organization.id(), organization.name(), view.role(), organization.createdAt());
    }
}
