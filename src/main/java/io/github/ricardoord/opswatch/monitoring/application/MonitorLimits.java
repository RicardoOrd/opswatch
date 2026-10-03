package io.github.ricardoord.opswatch.monitoring.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Quotas of the monitoring module (docs/devops/environments.md#cuotas). Its own properties: the quotas of
 * {@code organization} are internal to it.
 *
 * @param monitorsPerOrganization monitors not deleted in one organization, paused ones included. It bounds how much the
 *     engine can be used against third parties
 */
@ConfigurationProperties("opswatch.limits")
public record MonitorLimits(@DefaultValue("50") int monitorsPerOrganization) {

    public MonitorLimits {
        if (monitorsPerOrganization < 1) {
            throw new IllegalArgumentException("opswatch.limits.monitors-per-organization must be at least 1");
        }
    }
}
