package io.github.ricardoord.opswatch.notification.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A delivery a worker took, with its attempt already counted ({@link DeliveryQueue#claim}).
 *
 * @param incidentId null only for a {@link DeliveryEventType#TEST} delivery
 * @param attempt this one, from 1
 */
public record ClaimedDelivery(
        UUID id,
        UUID channelId,
        @Nullable UUID incidentId,
        DeliveryEventType eventType,
        int attempt,
        Instant createdAt) {}
