package io.github.ricardoord.opswatch.shared.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The event publication part of {@code opswatch.retention} (docs/devops/environments.md#retención). The schedule is
 * {@code opswatch.retention.cron}, read by {@link EventPublicationPurgeJob}.
 *
 * @param eventPublications how long a completed publication stays in the archive
 */
@ConfigurationProperties("opswatch.retention")
public record EventPublicationRetentionProperties(
        @DefaultValue("7d") Duration eventPublications) {

    public EventPublicationRetentionProperties {
        if (eventPublications.isNegative() || eventPublications.isZero()) {
            throw new IllegalArgumentException("opswatch.retention.event-publications must be positive");
        }
    }
}
