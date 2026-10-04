package io.github.ricardoord.opswatch.monitoring.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code monitor_checks}, with plain JDBC: an insert on every check, with no entity lifecycle to pay for
 * (docs/architecture/monitoring-engine.md#9-persistencia-del-resultado). Rows are never updated.
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
                // OffsetDateTime: the JDBC 4.2 type of timestamptz, which the PostgreSQL driver maps both ways
                .param("checkedAt", checkedAt.atOffset(ZoneOffset.UTC))
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
}
