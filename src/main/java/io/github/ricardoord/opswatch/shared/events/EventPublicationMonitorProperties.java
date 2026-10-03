package io.github.ricardoord.opswatch.shared.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Watching the pending event publications (docs/devops/environments.md#eventos). How often it checks is
 * {@code opswatch.events.incomplete-check-interval}, read by {@link IncompleteEventPublicationsMonitor}.
 *
 * @param incompleteAlertAfter how long a publication can stay pending before every check logs a warning about it
 */
@ConfigurationProperties("opswatch.events")
public record EventPublicationMonitorProperties(
        @DefaultValue("15m") Duration incompleteAlertAfter) {

    public EventPublicationMonitorProperties {
        if (incompleteAlertAfter.isNegative()) {
            throw new IllegalArgumentException("opswatch.events.incomplete-alert-after cannot be negative");
        }
    }
}
