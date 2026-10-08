package io.github.ricardoord.opswatch.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Quotas of the notification module (docs/devops/environments.md#cuotas). Together they bound how much OpsWatch can be
 * used to send to third parties (T-35) until the recipients are verified (Phase 5).
 *
 * @param channelsPerOrganization channels of one organization, disabled ones included
 * @param recipientsPerChannel addresses of one email channel
 */
@ConfigurationProperties("opswatch.limits")
public record NotificationLimits(
        @DefaultValue("10") int channelsPerOrganization,
        @DefaultValue("10") int recipientsPerChannel) {

    public NotificationLimits {
        if (channelsPerOrganization < 1) {
            throw new IllegalArgumentException("opswatch.limits.channels-per-organization must be at least 1");
        }
        if (recipientsPerChannel < 1) {
            throw new IllegalArgumentException("opswatch.limits.recipients-per-channel must be at least 1");
        }
    }
}
