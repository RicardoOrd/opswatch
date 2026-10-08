package io.github.ricardoord.opswatch.notification.domain;

/**
 * What a delivery tells. The names are stored as they are in {@code notification_deliveries.event_type}: renaming one
 * needs a migration.
 */
public enum DeliveryEventType {
    /** {@code IncidentOpened}. */
    INCIDENT_OPENED,
    /** {@code IncidentResolved}. */
    INCIDENT_RESOLVED,
    /** The test of a channel that someone asked for: no incident. */
    TEST
}
