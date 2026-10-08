package io.github.ricardoord.opswatch.notification.delivery;

import io.github.ricardoord.opswatch.incident.IncidentSummary;
import io.github.ricardoord.opswatch.notification.domain.DeliveryEventType;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What one delivery tells, rendered by a {@link ChannelSender} on each attempt.
 *
 * @param deliveryId the same on every attempt, so that a receiver can tell a repeated delivery
 * @param createdAt when the event was turned into this delivery
 * @param incident as it is now; null only for a {@link DeliveryEventType#TEST} delivery
 */
public record Notice(
        UUID deliveryId,
        DeliveryEventType eventType,
        Instant createdAt,
        String channelName,
        @Nullable IncidentSummary incident) {

    public Notice {
        if ((eventType == DeliveryEventType.TEST) != (incident == null)) {
            throw new IllegalArgumentException("Only a TEST notice has no incident");
        }
    }
}
