package io.github.ricardoord.opswatch.incident.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Opens incidents with plain JDBC, so that the database decides whether one is already active: an insert that the
 * unique index {@code ux_incidents_one_active_per_monitor} would reject is skipped, never an error that would roll back
 * the check that tried it.
 */
@Repository
public class NewIncidentRepository {

    private final JdbcClient jdbc;

    NewIncidentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param now when it is opened, for {@code created_at} and {@code updated_at}
     * @return false if the monitor already had an active incident, which stays as it was
     */
    public boolean insertIfNoneActive(NewIncident incident, Instant now) {
        int inserted = jdbc.sql("""
                        INSERT INTO incidents (id, organization_id, project_id, monitor_id, monitor_name, status, cause,
                                               cause_http_status, opened_at, created_at, updated_at)
                        VALUES (:id, :organizationId, :projectId, :monitorId, :monitorName, 'OPEN', :cause,
                                :causeHttpStatus, :openedAt, :now, :now)
                        ON CONFLICT (monitor_id) WHERE status <> 'RESOLVED' DO NOTHING""")
                .param("id", incident.id())
                .param("organizationId", incident.organizationId())
                .param("projectId", incident.projectId())
                .param("monitorId", incident.monitorId())
                .param("monitorName", incident.monitorName())
                .param("cause", incident.cause())
                .param("causeHttpStatus", incident.causeHttpStatus())
                .param("openedAt", at(incident.openedAt()))
                .param("now", at(now))
                .update();
        return inserted == 1;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
