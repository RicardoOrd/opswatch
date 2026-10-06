package io.github.ricardoord.opswatch.incident.domain;

/** What an entry of the timeline of an incident records. Stored as it is in {@code incident_timeline.type}. */
public enum TimelineEntryType {
    OPENED,
    ACKNOWLEDGED,
    RESOLVED
}
