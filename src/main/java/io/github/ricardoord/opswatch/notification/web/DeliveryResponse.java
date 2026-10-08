package io.github.ricardoord.opswatch.notification.web;

import io.github.ricardoord.opswatch.notification.domain.DeliveryEventType;
import io.github.ricardoord.opswatch.notification.domain.DeliveryStatus;
import io.github.ricardoord.opswatch.notification.domain.NotificationDelivery;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A delivery as the API shows it: its status and attempts, never what was sent.
 *
 * @param incidentId null for a {@code TEST} delivery
 * @param nextAttemptAt null once it is {@code SENT} or {@code FAILED}
 * @param lastError what went wrong in the last attempt, never a recipient or a URL
 */
record DeliveryResponse(
        UUID id,
        UUID channelId,
        @Nullable UUID incidentId,
        DeliveryEventType eventType,
        DeliveryStatus status,
        int attempts,
        @Nullable Instant nextAttemptAt,
        @Nullable Instant lastAttemptAt,
        @Nullable String lastError,
        Instant createdAt,
        @Nullable Instant sentAt) {

    static DeliveryResponse from(NotificationDelivery delivery) {
        return new DeliveryResponse(
                delivery.id(),
                delivery.channelId(),
                delivery.incidentId(),
                delivery.eventType(),
                delivery.status(),
                delivery.attempts(),
                delivery.nextAttemptAt(),
                delivery.lastAttemptAt(),
                delivery.lastError(),
                delivery.createdAt(),
                delivery.sentAt());
    }
}
