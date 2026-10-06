package io.github.ricardoord.opswatch.incident.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the database guarantees about incidents whatever the application does: at most one active incident per monitor
 * (rule R2), and resolutions that are complete.
 */
@IntegrationTest
class IncidentRepositoryIT {

    @Autowired
    private NewIncidentRepository openings;

    @Autowired
    private IncidentRepository incidents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void aSecondActiveIncidentForTheSameMonitorBreaksTheUniqueIndex() {
        Monitor monitor = newMonitor();
        insertByHand(monitor, "OPEN");

        assertThatThrownBy(() -> insertByHand(monitor, "ACKNOWLEDGED"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("ux_incidents_one_active_per_monitor");
    }

    @Test
    void resolvedIncidentsDoNotCountAsActive() {
        Monitor monitor = newMonitor();
        insertResolvedByHand(monitor);
        insertResolvedByHand(monitor);

        insertByHand(monitor, "OPEN");

        assertThat(countOf(monitor)).isEqualTo(3);
    }

    @Test
    void anOpeningWithAnIncidentAlreadyActiveIsSkippedWithoutAnError() {
        Monitor monitor = newMonitor();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        boolean first = openings.insertIfNoneActive(incident(monitor, now), now);
        boolean second = openings.insertIfNoneActive(incident(monitor, now.plusSeconds(60)), now);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(countOf(monitor)).isOne();
    }

    @Test
    void findsOnlyTheActiveIncidentOfAMonitor() {
        Monitor monitor = newMonitor();
        insertResolvedByHand(monitor);
        UUID active = insertByHand(monitor, "ACKNOWLEDGED");

        UUID found = transactions.execute(tx -> incidents
                .findActiveByMonitorIdForUpdate(monitor.id())
                .orElseThrow()
                .id());

        assertThat(found).isEqualTo(active);
    }

    @Test
    void aResolvedIncidentWithoutItsResolutionIsRejected() {
        Monitor monitor = newMonitor();

        assertThatThrownBy(() ->
                        jdbc.update("""
                        INSERT INTO incidents (id, organization_id, project_id, monitor_id, monitor_name, status, cause,
                                               opened_at, resolved_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'Payments API', 'RESOLVED', 'TIMEOUT', now(), now(), now(), now())""", UUID.randomUUID(), monitor.organization(), monitor.project(), monitor.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_incidents_resolved");
    }

    private static NewIncident incident(Monitor monitor, Instant openedAt) {
        return new NewIncident(
                UUID.randomUUID(),
                monitor.organization(),
                monitor.project(),
                monitor.id(),
                "Payments API",
                "TIMEOUT",
                null,
                openedAt);
    }

    private UUID insertByHand(Monitor monitor, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incidents (id, organization_id, project_id, monitor_id, monitor_name, status, cause, opened_at,
                                       acknowledged_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Payments API', ?, 'TIMEOUT', now(), now(), now(), now())""", id, monitor.organization(), monitor.project(), monitor.id(), status);
        return id;
    }

    private void insertResolvedByHand(Monitor monitor) {
        jdbc.update("""
                INSERT INTO incidents (id, organization_id, project_id, monitor_id, monitor_name, status, cause, opened_at,
                                       resolved_at, resolution, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Payments API', 'RESOLVED', 'TIMEOUT', now(), now(), 'AUTO_RECOVERED', now(), now())""", UUID.randomUUID(), monitor.organization(), monitor.project(), monitor.id());
    }

    private long countOf(Monitor monitor) {
        Long found =
                jdbc.queryForObject("SELECT count(*) FROM incidents WHERE monitor_id = ?", Long.class, monitor.id());
        return found == null ? 0 : found;
    }

    /** No row of {@code monitor_state}: nothing schedules it. */
    private Monitor newMonitor() {
        UUID organization = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                organization);
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, 'Production', now(), now())""", project, organization);
        UUID monitor = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO monitors (id, organization_id, project_id, name, url, created_at, updated_at)
                VALUES (?, ?, ?, 'Payments API', 'https://api.example.com/health', now(), now())""", monitor, organization, project);
        return new Monitor(monitor, organization, project);
    }

    private record Monitor(UUID id, UUID organization, UUID project) {}
}
