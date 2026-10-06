package io.github.ricardoord.opswatch.incident.application;

import io.github.ricardoord.opswatch.incident.domain.Incident;
import io.github.ricardoord.opswatch.incident.domain.TimelineEntryType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An incident with its timeline and the people in it, by name.
 *
 * @param acknowledgedBy null if nobody acknowledged it, or that user's account is deleted
 */
public record IncidentDetail(Incident incident, @Nullable Actor acknowledgedBy, List<Entry> timeline) {

    public IncidentDetail {
        timeline = List.copyOf(timeline);
    }

    /**
     * Someone who did something to the incident.
     *
     * @param displayName as the user is called now
     */
    public record Actor(UUID id, String displayName) {}

    /**
     * @param actor null for what the system did, and once that user's account is deleted
     * @param note only on an acknowledgement, and only if one was given
     */
    public record Entry(
            TimelineEntryType type,
            Instant occurredAt,
            @Nullable Actor actor,
            @Nullable String note) {}
}
