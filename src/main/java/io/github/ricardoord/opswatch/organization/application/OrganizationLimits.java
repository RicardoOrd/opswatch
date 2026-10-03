package io.github.ricardoord.opswatch.organization.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Quotas of the organization module (docs/devops/environments.md#cuotas).
 *
 * @param organizationsPerUser organizations not deleted that one user can own
 * @param membersPerOrganization members of one organization, owners included
 * @param projectsPerOrganization projects not deleted in one organization
 */
@ConfigurationProperties("opswatch.limits")
public record OrganizationLimits(
        @DefaultValue("5") int organizationsPerUser,
        @DefaultValue("50") int membersPerOrganization,
        @DefaultValue("20") int projectsPerOrganization) {

    public OrganizationLimits {
        if (organizationsPerUser < 1 || membersPerOrganization < 1 || projectsPerOrganization < 1) {
            throw new IllegalArgumentException("opswatch.limits.organizations-per-user, members-per-organization and"
                    + " projects-per-organization must be at least 1");
        }
    }
}
