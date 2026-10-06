package io.github.ricardoord.opswatch.incident.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code incident_timeline}, with plain JDBC: append-only, so there is no entity lifecycle to pay for. Read back with the detail of an incident (OW-033). */
@Repository
public class IncidentTimelineRepository {

    private final JdbcClient jdbc;

    IncidentTimelineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param actor who did it; null for what the system did
     * @param note only on an acknowledgement; null for none
     */
    public void append(
            UUID id,
            UUID incidentId,
            TimelineEntryType type,
            @Nullable UUID actor,
            Instant occurredAt,
            @Nullable String note) {
        jdbc.sql("""
                        INSERT INTO incident_timeline (id, incident_id, type, actor_user_id, occurred_at, note)
                        VALUES (:id, :incidentId, :type, :actor, :occurredAt, :note)""")
                .param("id", id)
                .param("incidentId", incidentId)
                .param("type", type.name())
                .param("actor", actor)
                .param("occurredAt", at(occurredAt))
                .param("note", note)
                .update();
    }

    /** Oldest first; entries of the same instant in the order they were written (ids are time-ordered). */
    public List<TimelineEntry> findByIncident(UUID incidentId) {
        return jdbc.sql("""
                        SELECT id, type, actor_user_id, occurred_at, note
                        FROM incident_timeline
                        WHERE incident_id = :incidentId
                        ORDER BY occurred_at, id""")
                .param("incidentId", incidentId)
                .query((row, number) -> new TimelineEntry(
                        row.getObject("id", UUID.class),
                        TimelineEntryType.valueOf(row.getString("type")),
                        row.getObject("actor_user_id", UUID.class),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        row.getString("note")))
                .list();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
