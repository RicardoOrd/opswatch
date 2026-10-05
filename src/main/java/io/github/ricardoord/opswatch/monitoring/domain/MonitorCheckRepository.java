package io.github.ricardoord.opswatch.monitoring.domain;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code monitor_checks}, with plain JDBC: an insert on every check, with no entity lifecycle to pay for
 * (docs/architecture/monitoring-engine.md#9-persistencia-del-resultado). Rows are never updated. Read back by the
 * history and the statistics of a monitor (OW-028).
 */
@Repository
public class MonitorCheckRepository {

    private final JdbcClient jdbc;

    MonitorCheckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param checkedAt when the check started, to the microsecond: with the monitor, the primary key */
    public void insert(UUID monitorId, Instant checkedAt, CheckOutcome outcome) {
        jdbc.sql("""
                        INSERT INTO monitor_checks
                            (monitor_id, checked_at, status, http_status, response_time_ms, failure_reason, error_detail)
                        VALUES
                            (:monitorId, :checkedAt, :status, :httpStatus, :responseTimeMs, :failureReason, :errorDetail)""")
                .param("monitorId", monitorId)
                .param("checkedAt", at(checkedAt))
                .param("status", outcome.status().name())
                .param("httpStatus", outcome.httpStatus())
                .param("responseTimeMs", outcome.responseTimeMs())
                .param(
                        "failureReason",
                        outcome.failureReason() == null
                                ? null
                                : outcome.failureReason().name())
                .param("errorDetail", outcome.errorDetail())
                .update();
    }
    /**
     * A page of the history of a monitor, newest first. Its primary key, read backwards, serves the order and the
     * position: no {@code OFFSET}, which degrades with every page.
     *
     * @param statuses only these; empty for every status
     * @param from inclusive; null for no lower bound
     * @param to exclusive; null for no upper bound
     * @param after the position of the cursor: only checks older than it; null for the first page
     * @param rows at most this many
     */
    public List<RecordedCheck> findPage(
            UUID monitorId,
            Set<CheckStatus> statuses,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable Instant after,
            int rows) {
        StringBuilder sql = new StringBuilder("""
                SELECT checked_at, status, http_status, response_time_ms, failure_reason, error_detail
                FROM monitor_checks
                WHERE monitor_id = :monitorId""");
        Map<String, Object> params = new HashMap<>();
        params.put("monitorId", monitorId);
        params.put("rows", rows);
        if (!statuses.isEmpty()) {
            sql.append(" AND status IN (:statuses)");
            params.put("statuses", statuses.stream().map(Enum::name).toList());
        }
        if (from != null) {
            sql.append(" AND checked_at >= :from");
            params.put("from", at(from));
        }
        if (to != null) {
            sql.append(" AND checked_at < :to");
            params.put("to", at(to));
        }
        if (after != null) {
            sql.append(" AND checked_at < :after");
            params.put("after", at(after));
        }
        sql.append(" ORDER BY checked_at DESC LIMIT :rows");
        return jdbc.sql(sql.toString())
                .params(params)
                .query((row, number) -> new RecordedCheck(
                        row.getObject("checked_at", OffsetDateTime.class).toInstant(),
                        new CheckOutcome(
                                CheckStatus.valueOf(row.getString("status")),
                                row.getObject("http_status", Integer.class),
                                millis(row.getObject("response_time_ms", Integer.class)),
                                reason(row.getString("failure_reason")),
                                row.getString("error_detail"))))
                .list();
    }

    /**
     * The checks of a monitor that started in {@code [from, to)}, summed up in PostgreSQL ({@code FILTER} and
     * {@code percentile_cont}, which skip the checks without a response time). Two queries: run them in one
     * transaction with a single snapshot, or a check recorded between them would count in one and not in the other.
     */
    public CheckStats statsOf(UUID monitorId, Instant from, Instant to) {
        Map<String, Object> window = Map.of("monitorId", monitorId, "from", at(from), "to", at(to));
        Map<FailureReason, Long> failures = new EnumMap<>(FailureReason.class);
        jdbc.sql("""
                        SELECT failure_reason, count(*) AS checks
                        FROM monitor_checks
                        WHERE monitor_id = :monitorId AND checked_at >= :from AND checked_at < :to
                            AND failure_reason IS NOT NULL
                        GROUP BY failure_reason""").params(window).query(row -> {
            failures.put(FailureReason.valueOf(row.getString("failure_reason")), row.getLong("checks"));
        });
        return jdbc.sql("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE status = 'UP') AS up,
                               count(*) FILTER (WHERE status = 'DEGRADED') AS degraded,
                               count(*) FILTER (WHERE status = 'DOWN') AS down,
                               round(avg(response_time_ms))::integer AS avg_ms,
                               round((percentile_cont(0.5) WITHIN GROUP (ORDER BY response_time_ms))::numeric)::integer
                                   AS p50_ms,
                               round((percentile_cont(0.95) WITHIN GROUP (ORDER BY response_time_ms))::numeric)::integer
                                   AS p95_ms,
                               round((percentile_cont(0.99) WITHIN GROUP (ORDER BY response_time_ms))::numeric)::integer
                                   AS p99_ms
                        FROM monitor_checks
                        WHERE monitor_id = :monitorId AND checked_at >= :from AND checked_at < :to""")
                .params(window)
                .query((row, number) -> new CheckStats(
                        row.getLong("total"),
                        row.getLong("up"),
                        row.getLong("degraded"),
                        row.getLong("down"),
                        new CheckStats.ResponseTimes(
                                row.getObject("avg_ms", Integer.class),
                                row.getObject("p50_ms", Integer.class),
                                row.getObject("p95_ms", Integer.class),
                                row.getObject("p99_ms", Integer.class)),
                        failures))
                .single();
    }

    /**
     * Deletes up to {@code batch} checks that started before {@code cutoff} (docs/database/data-retention.md#job-de-purga-de-checks).
     * {@code SKIP LOCKED}: another instance purging at the same time takes other rows, and neither waits for the
     * other. By {@code ctid}, since the table has no single-column key. Run it in a short transaction of its own.
     *
     * @return how many it deleted; fewer than {@code batch} when nothing is left
     */
    public int deleteOlderThan(Instant cutoff, int batch) {
        return jdbc.sql("""
                        DELETE FROM monitor_checks
                        WHERE ctid = ANY (ARRAY(
                            SELECT ctid FROM monitor_checks
                            WHERE checked_at < :cutoff
                            LIMIT :batch
                            FOR UPDATE SKIP LOCKED))""").param("cutoff", at(cutoff)).param("batch", batch).update();
    }

    /**
     * Deletes up to {@code batch} checks of deleted monitors, whatever their age: nobody can read them any more. As
     * {@link #deleteOlderThan}, in a short transaction of its own.
     *
     * @return how many it deleted; fewer than {@code batch} when nothing is left
     */
    public int deleteOfDeletedMonitors(int batch) {
        return jdbc.sql("""
                        DELETE FROM monitor_checks
                        WHERE ctid = ANY (ARRAY(
                            SELECT c.ctid FROM monitor_checks c
                            JOIN monitors m ON m.id = c.monitor_id
                            WHERE m.deleted_at IS NOT NULL
                            LIMIT :batch
                            FOR UPDATE OF c SKIP LOCKED))""").param("batch", batch).update();
    }

    /** OffsetDateTime: the JDBC 4.2 type of timestamptz, which the PostgreSQL driver maps both ways. */
    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Duration millis(@Nullable Integer milliseconds) {
        return milliseconds == null ? null : Duration.ofMillis(milliseconds);
    }

    private static @Nullable FailureReason reason(@Nullable String name) {
        return name == null ? null : FailureReason.valueOf(name);
    }
}
