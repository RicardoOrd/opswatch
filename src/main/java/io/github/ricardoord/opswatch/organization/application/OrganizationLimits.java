package io.github.ricardoord.opswatch.organization.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Quotas of the organization module (docs/devops/environments.md#cuotas).
 *
 * @param organizationsPerUser organizations not deleted that one user can own
 */
@ConfigurationProperties("opswatch.limits")
public record OrganizationLimits(@DefaultValue("5") int organizationsPerUser) {

    public OrganizationLimits {
        if (organizationsPerUser < 1) {
            throw new IllegalArgumentException("opswatch.limits.organizations-per-user must be at least 1");
        }
    }
}
