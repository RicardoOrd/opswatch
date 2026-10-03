package io.github.ricardoord.opswatch.shared.events;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rows of the registry tables written straight with SQL, in the shape Spring Modulith 2.1.1 leaves them: what a
 * crash, a failing listener or an old completion would have left. Remembers them so that {@link #deleteAll()} removes
 * exactly these and nothing else of the shared database.
 */
final class EventPublicationRows {

    static final String PENDING = "event_publication";
    static final String ARCHIVE = "event_publication_archive";

    private final JdbcClient jdbc;
    private final List<UUID> inserted = new ArrayList<>();

    EventPublicationRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A publication whose listener has not completed, as a failure leaves it. */
    UUID pending(Instant publishedAt) {
        return insert(PENDING, publishedAt, null, "FAILED");
    }

    UUID archived(Instant completedAt) {
        return insert(ARCHIVE, completedAt.minusSeconds(1), completedAt, "COMPLETED");
    }

    /** Completed but still in the pending table, as {@code completion-mode=update} would leave it. */
    UUID completedInThePendingTable(Instant completedAt) {
        return insert(PENDING, completedAt.minusSeconds(1), completedAt, "COMPLETED");
    }

    boolean exists(String table, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                > 0;
    }

    void deleteAll() {
        if (inserted.isEmpty()) {
            return;
        }
        for (String table : List.of(PENDING, ARCHIVE)) {
            jdbc.sql("DELETE FROM " + table + " WHERE id IN (:ids)")
                    .param("ids", inserted)
                    .update();
        }
        inserted.clear();
    }

    private UUID insert(String table, Instant publishedAt, @Nullable Instant completedAt, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + table + """
                         (id, listener_id, event_type, serialized_event, publication_date, completion_date, status,
                         completion_attempts)
                        VALUES (:id, :listener, :type, :event, :publishedAt, :completedAt, :status, 1)""")
                .param("id", id)
                .param("listener", "io.github.ricardoord.opswatch.test.NoSuchListener.on(java.lang.String)")
                .param("type", String.class.getName())
                .param("event", "\"" + id + "\"")
                .param("publishedAt", publishedAt.atOffset(ZoneOffset.UTC))
                .param("completedAt", completedAt == null ? null : completedAt.atOffset(ZoneOffset.UTC))
                .param("status", status)
                .update();
        inserted.add(id);
        return id;
    }
}
