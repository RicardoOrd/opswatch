package io.github.ricardoord.opswatch.incident.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the timeline of an incident.
 *
 * @param actor who did it; null for what the system did, and once that user's account is deleted
 * @param note only on an acknowledgement, and only if one was given
 */
public record TimelineEntry(
        UUID id,
        TimelineEntryType type,
        @Nullable UUID actor,
        Instant occurredAt,
        @Nullable String note) {}
