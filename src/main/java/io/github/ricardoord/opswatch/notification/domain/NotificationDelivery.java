package io.github.ricardoord.opswatch.notification.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One notification of one event to one channel, and how its attempts went
 * (docs/architecture/domain-model.md#notificationdelivery). Read only: {@link DeliveryQueue} writes it with plain SQL,
 * because the database decides what is new ({@code ON CONFLICT}) and which worker takes it ({@code SKIP LOCKED}).
 *
 * <p>Never what was sent: the content is rendered on each attempt from the incident.
 */
@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    @Id
    private UUID id;

    private UUID channelId;

    /** Null only for a {@link DeliveryEventType#TEST} delivery. */
    private @Nullable UUID incidentId;

    @Enumerated(EnumType.STRING)
    private DeliveryEventType eventType;

    @Enumerated(EnumType.STRING)
    private DeliveryStatus status;

    private int attempts;

    /** Null once it is {@code SENT} or {@code FAILED}. */
    private @Nullable Instant nextAttemptAt;

    private @Nullable Instant lastAttemptAt;

    /** What went wrong in the last attempt, never with a recipient or a URL. */
    private @Nullable String lastError;

    private Instant createdAt;

    private @Nullable Instant sentAt;

    /** For JPA, which populates the fields. */
    protected NotificationDelivery() {}

    public UUID id() {
        return id;
    }

    public UUID channelId() {
        return channelId;
    }

    public @Nullable UUID incidentId() {
        return incidentId;
    }

    public DeliveryEventType eventType() {
        return eventType;
    }

    public DeliveryStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public @Nullable Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public @Nullable Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public @Nullable Instant sentAt() {
        return sentAt;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof NotificationDelivery delivery && id.equals(delivery.id()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "NotificationDelivery[id=" + id + ", eventType=" + eventType + ", status=" + status + "]";
    }
}
