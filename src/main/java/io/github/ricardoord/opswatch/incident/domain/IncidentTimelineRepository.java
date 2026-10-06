package io.github.ricardoord.opswatch.incident.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code incident_timeline}, with plain JDBC: append-only, so there is no entity lifecycle to pay for. */
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

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
