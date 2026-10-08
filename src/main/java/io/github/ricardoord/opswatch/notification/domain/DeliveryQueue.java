package io.github.ricardoord.opswatch.notification.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code notification_deliveries} as a queue in the database, with plain SQL (docs/architecture/events.md#7-flujo-de-eventos):
 * the listener of the incident events adds to it, and the workers of every instance take from it with
 * {@code FOR UPDATE SKIP LOCKED}, so that none takes a delivery another one has.
 */
@Repository
public class DeliveryQueue {

    /**
     * Only the row of the delivery: a {@code PATCH} of its channel is never held up by a claim. The attempt is counted
     * and the delivery set aside until {@code :leaseUntil} in the same statement, so that no other worker takes it while
     * it is being sent; if this one stops halfway, the lease runs out and another one sends it again (at least once).
     */
    static final String CLAIM = """
            WITH due AS (
                SELECT d.id
                FROM notification_deliveries d
                JOIN notification_channels c ON c.id = d.channel_id
                WHERE d.status = 'PENDING'
                    AND d.next_attempt_at <= :now
                    AND c.type IN (:types)
                ORDER BY d.next_attempt_at
                LIMIT :max
                FOR UPDATE OF d SKIP LOCKED
            )
            UPDATE notification_deliveries d
            SET attempts = d.attempts + 1,
                last_attempt_at = :now,
                next_attempt_at = :leaseUntil
            FROM due
            WHERE d.id = due.id
            RETURNING d.id, d.channel_id, d.incident_id, d.event_type, d.attempts, d.created_at""";

    private final JdbcClient jdbc;

    DeliveryQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The channels that take the incidents of a project: enabled, of its organization, and limited to that project or
     * to none. Each is held with {@code FOR KEY SHARE} until the transaction ends, so that a channel deleted meanwhile
     * waits for its deliveries to be written and deletes them with it, instead of failing their foreign key.
     */
    public List<UUID> lockChannelsFor(UUID organizationId, UUID projectId) {
        return jdbc.sql("""
                        SELECT id FROM notification_channels
                        WHERE organization_id = :organizationId
                            AND (project_id IS NULL OR project_id = :projectId)
                            AND enabled
                        ORDER BY id
                        FOR KEY SHARE""")
                .param("organizationId", organizationId)
                .param("projectId", projectId)
                .query(UUID.class)
                .list();
    }

    /**
     * Due at {@code dueAt}. A second one for the same channel, incident and event is skipped by
     * {@code ux_notification_deliveries_once}: an event delivered twice notifies once.
     *
     * @return false if that delivery already existed
     */
    public boolean addForIncident(
            UUID id, UUID channelId, UUID incidentId, DeliveryEventType eventType, Instant now, Instant dueAt) {
        if (eventType == DeliveryEventType.TEST) {
            throw new IllegalArgumentException("A TEST delivery has no incident");
        }
        return jdbc.sql("""
                        INSERT INTO notification_deliveries (id, channel_id, incident_id, event_type, next_attempt_at,
                                                             created_at)
                        VALUES (:id, :channelId, :incidentId, :eventType, :dueAt, :now)
                        ON CONFLICT ON CONSTRAINT ux_notification_deliveries_once DO NOTHING""")
                        .param("id", id)
                        .param("channelId", channelId)
                        .param("incidentId", incidentId)
                        .param("eventType", eventType.name())
                        .param("now", at(now))
                        .param("dueAt", at(dueAt))
                        .update()
                == 1;
    }

    /**
     * A {@link DeliveryEventType#TEST} delivery, due at {@code dueAt}, if the channel still exists: its row is held with
     * {@code FOR KEY SHARE} as in {@link #lockChannelsFor}, and one deleted meanwhile gives no row.
     *
     * @return false if the channel no longer exists
     */
    public boolean addTest(UUID id, UUID channelId, Instant now, Instant dueAt) {
        return jdbc.sql("""
                        INSERT INTO notification_deliveries (id, channel_id, event_type, next_attempt_at, created_at)
                        SELECT :id, c.id, 'TEST', :dueAt, :now
                        FROM notification_channels c
                        WHERE c.id = :channelId
                        FOR KEY SHARE""")
                        .param("id", id)
                        .param("channelId", channelId)
                        .param("now", at(now))
                        .param("dueAt", at(dueAt))
                        .update()
                == 1;
    }

    /**
     * Takes the deliveries that are due, the most overdue first, and counts their attempt. Run it in a short
     * transaction of its own: the rows stay locked until it commits.
     *
     * @param types only the deliveries of channels of these types: those this instance can send
     * @param leaseUntil until when no other worker takes them
     */
    public List<ClaimedDelivery> claim(int max, Instant now, Instant leaseUntil, Collection<ChannelType> types) {
        if (types.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(CLAIM)
                .param("now", at(now))
                .param("leaseUntil", at(leaseUntil))
                .param("max", max)
                .param("types", types.stream().map(ChannelType::name).toList())
                .query((row, number) -> new ClaimedDelivery(
                        row.getObject("id", UUID.class),
                        row.getObject("channel_id", UUID.class),
                        row.getObject("incident_id", UUID.class),
                        DeliveryEventType.valueOf(row.getString("event_type")),
                        row.getInt("attempts"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Only if the delivery is still in that attempt: a worker whose lease ran out, and whose delivery another one took
     * again, does not write over the newer attempt.
     *
     * @return false if the delivery moved on, or its channel was deleted with it
     */
    public boolean recordSent(UUID id, int attempt, Instant at) {
        return jdbc.sql("""
                        UPDATE notification_deliveries
                        SET status = 'SENT', sent_at = :at, next_attempt_at = NULL, last_error = NULL
                        WHERE id = :id AND attempts = :attempt AND status = 'PENDING'""")
                        .param("id", id)
                        .param("attempt", attempt)
                        .param("at", at(at))
                        .update()
                == 1;
    }

    /**
     * A failed attempt with more to come. As {@link #recordSent}, only if the delivery is still in that attempt.
     *
     * @param error what went wrong, at most 255 characters, never with a recipient or a URL
     * @return false if the delivery moved on, or its channel was deleted with it
     */
    public boolean recordRetry(UUID id, int attempt, String error, Instant retryAt) {
        return jdbc.sql("""
                        UPDATE notification_deliveries
                        SET next_attempt_at = :retryAt, last_error = :error
                        WHERE id = :id AND attempts = :attempt AND status = 'PENDING'""")
                        .param("id", id)
                        .param("attempt", attempt)
                        .param("error", error)
                        .param("retryAt", at(retryAt))
                        .update()
                == 1;
    }

    /**
     * No more attempts. As {@link #recordSent}, only if the delivery is still in that attempt.
     *
     * @param error what went wrong, at most 255 characters, never with a recipient or a URL
     * @return false if the delivery moved on, or its channel was deleted with it
     */
    public boolean recordFailed(UUID id, int attempt, String error) {
        return jdbc.sql("""
                        UPDATE notification_deliveries
                        SET status = 'FAILED', next_attempt_at = NULL, last_error = :error
                        WHERE id = :id AND attempts = :attempt AND status = 'PENDING'""")
                        .param("id", id)
                        .param("attempt", attempt)
                        .param("error", error)
                        .update()
                == 1;
    }

    /**
     * Deletes up to {@code batch} deliveries created before {@code cutoff}, whatever their status
     * (docs/database/data-retention.md). {@code SKIP LOCKED}: another instance purging at the same time takes other
     * rows, and a delivery being claimed is left for the next purge. Run it in a short transaction of its own.
     *
     * @return how many it deleted; fewer than {@code batch} when nothing is left
     */
    public int deleteCreatedBefore(Instant cutoff, int batch) {
        return jdbc.sql("""
                        DELETE FROM notification_deliveries
                        WHERE id = ANY (ARRAY(
                            SELECT id FROM notification_deliveries
                            WHERE created_at < :cutoff
                            LIMIT :batch
                            FOR UPDATE SKIP LOCKED))""").param("cutoff", at(cutoff)).param("batch", batch).update();
    }

    /** OffsetDateTime: the JDBC 4.2 type of timestamptz, which the PostgreSQL driver maps both ways. */
    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
