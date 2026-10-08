package io.github.ricardoord.opswatch.notification.domain;

/** Stored as it is in {@code notification_deliveries.status}: renaming one needs a migration. */
public enum DeliveryStatus {
    /** Waiting for its next attempt, or being attempted. */
    PENDING,
    SENT,
    /** Every attempt failed, or its channel was disabled: no more attempts. */
    FAILED
}
