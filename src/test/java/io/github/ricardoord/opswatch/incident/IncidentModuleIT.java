package io.github.ricardoord.opswatch.incident;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.PostgresTestcontainer;
import io.github.ricardoord.opswatch.TestEncryptionKeys;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.MonitorDeleted;
import io.github.ricardoord.opswatch.monitoring.MonitorPaused;
import io.github.ricardoord.opswatch.monitoring.MonitorRecovered;
import io.github.ricardoord.opswatch.monitoring.MonitorWentDown;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * The incident module alone, as Spring Modulith bootstraps it, with the open module {@code shared}: what it does with
 * the events of the monitor. {@code monitoring} is absent, so the monitors, their projects and their users are rows
 * inserted by hand, and each event is published as {@code monitoring} would, inside a transaction.
 */
@ApplicationModuleTest(extraIncludes = "shared")
@Import({PostgresTestcontainer.class, TestEncryptionKeys.class})
@ActiveProfiles("test")
class IncidentModuleIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher publisher;

    /** {@code MonitorWentDown} → an {@code OPEN} incident with what the check saw, its timeline and its event. */
    @Test
    void aMonitorThatGoesDownOpensAnIncident(Scenario scenario) {
        Monitor monitor = newMonitor();
        Instant at = now();

        IncidentOpened opened = arrived(
                scenario,
                wentDown(monitor, at, FailureReason.UNEXPECTED_STATUS, 503),
                IncidentOpened.class,
                event -> event.monitorId().equals(monitor.id()));

        assertThat(opened)
                .isEqualTo(new IncidentOpened(
                        opened.incidentId(),
                        monitor.organization(),
                        monitor.project(),
                        monitor.id(),
                        "Payments API",
                        at,
                        "UNEXPECTED_STATUS",
                        503));
        Map<String, Object> incident = incidentRow(opened.incidentId());
        assertThat(incident)
                .containsEntry("status", "OPEN")
                .containsEntry("monitor_name", "Payments API")
                .containsEntry("cause", "UNEXPECTED_STATUS")
                .containsEntry("cause_http_status", 503)
                .containsEntry("resolved_at", null)
                .containsEntry("resolution", null);
        assertThat(timelineOf(opened.incidentId())).containsExactly("OPENED");
    }

    /** Delivery of an event can repeat; the transition it announces happened once (rule R2). */
    @Test
    void aSecondWentDownOpensNoSecondIncident(Scenario scenario, AssertablePublishedEvents events) {
        Monitor monitor = newMonitor();
        MonitorWentDown down = wentDown(monitor, now(), FailureReason.TIMEOUT, null);
        publishInTransaction(scenario, down);

        publishInTransaction(scenario, down);
        publishInTransaction(scenario, wentDown(monitor, now().plusSeconds(60), FailureReason.TIMEOUT, null));

        assertThat(activeIncidentsOf(monitor)).isOne();
        assertThat(events.ofType(IncidentOpened.class)
                        .matching(event -> event.monitorId().equals(monitor.id())))
                .hasSize(1);
    }

    @Test
    void aRecoveryResolvesTheIncidentOnItsOwn(Scenario scenario) {
        Monitor monitor = newMonitor();
        Instant downAt = now();
        IncidentOpened opened = open(scenario, monitor, downAt);
        Instant upAt = downAt.plusSeconds(600);

        IncidentResolved resolved = arrived(
                scenario,
                new MonitorRecovered(
                        monitor.id(), monitor.organization(), monitor.project(), "Payments API", upAt, downAt),
                IncidentResolved.class,
                event -> event.monitorId().equals(monitor.id()));

        assertThat(resolved)
                .isEqualTo(new IncidentResolved(
                        opened.incidentId(),
                        monitor.organization(),
                        monitor.project(),
                        monitor.id(),
                        "Payments API",
                        downAt,
                        upAt,
                        Resolution.AUTO_RECOVERED));
        assertThat(incidentRow(opened.incidentId()))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolution", "AUTO_RECOVERED")
                .containsEntry("resolved_by", null);
        assertThat(timelineOf(opened.incidentId())).containsExactly("OPENED", "RESOLVED");
    }

    /** Rule R5: pausing a monitor that is down resolves its incident, and says who did it. */
    @Test
    void pausingAMonitorThatIsDownResolvesItsIncident(Scenario scenario) {
        Monitor monitor = newMonitor();
        IncidentOpened opened = open(scenario, monitor, now());
        UUID pausedBy = newUser();

        IncidentResolved resolved = arrived(
                scenario,
                new MonitorPaused(
                        monitor.id(), monitor.organization(), monitor.project(), now().plusSeconds(60), pausedBy),
                IncidentResolved.class,
                event -> event.monitorId().equals(monitor.id()));

        assertThat(resolved.resolution()).isEqualTo(Resolution.MONITOR_PAUSED);
        assertThat(incidentRow(opened.incidentId()))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolution", "MONITOR_PAUSED")
                .containsEntry("resolved_by", pausedBy);
        assertThat(jdbc.queryForObject(
                        "SELECT actor_user_id FROM incident_timeline WHERE incident_id = ? AND type = 'RESOLVED'",
                        UUID.class,
                        opened.incidentId()))
                .isEqualTo(pausedBy);
    }

    /** Also when the cleanup of a deleted project deletes it: the event carries who deleted the project. */
    @Test
    void deletingAMonitorThatIsDownResolvesItsIncident(Scenario scenario) {
        Monitor monitor = newMonitor();
        IncidentOpened opened = open(scenario, monitor, now());
        UUID deletedBy = newUser();

        scenario.publish(new MonitorDeleted(
                        monitor.id(), monitor.organization(), monitor.project(), now().plusSeconds(60), deletedBy))
                .andWaitForEventOfType(IncidentResolved.class)
                .matching(event -> event.monitorId().equals(monitor.id()))
                .toArriveAndVerify(event -> assertThat(event.resolution()).isEqualTo(Resolution.MONITOR_DELETED));

        assertThat(incidentRow(opened.incidentId()))
                .containsEntry("resolution", "MONITOR_DELETED")
                .containsEntry("resolved_by", deletedBy);
    }

    /** Pausing or deleting a monitor that is up has nothing to resolve. */
    @Test
    void pausingOrDeletingAMonitorWithoutAnIncidentResolvesNothing(
            Scenario scenario, AssertablePublishedEvents events) {
        Monitor monitor = newMonitor();
        UUID user = newUser();

        publishInTransaction(
                scenario, new MonitorPaused(monitor.id(), monitor.organization(), monitor.project(), now(), user));
        publishInTransaction(
                scenario, new MonitorDeleted(monitor.id(), monitor.organization(), monitor.project(), now(), user));

        assertThat(incidentsOf(monitor)).isZero();
        assertThat(events.ofType(IncidentResolved.class)
                        .matching(event -> event.monitorId().equals(monitor.id())))
                .isEmpty();
    }

    /** Rule R8: a resolved incident stays as it was, and the next fall of the monitor opens a new one. */
    @Test
    void aMonitorThatGoesDownAgainOpensANewIncident(Scenario scenario) {
        Monitor monitor = newMonitor();
        Instant first = now();
        IncidentOpened opened = open(scenario, monitor, first);
        publishInTransaction(
                scenario,
                new MonitorRecovered(
                        monitor.id(),
                        monitor.organization(),
                        monitor.project(),
                        "Payments API",
                        first.plusSeconds(120),
                        first));

        IncidentOpened reopened = open(scenario, monitor, first.plusSeconds(300));

        assertThat(reopened.incidentId()).isNotEqualTo(opened.incidentId());
        assertThat(incidentsOf(monitor)).isEqualTo(2);
        assertThat(activeIncidentsOf(monitor)).isOne();
        assertThat(incidentRow(opened.incidentId())).containsEntry("status", "RESOLVED");
    }

    /**
     * The listener only runs inside the transaction of the publisher: outside one, the invariant between the state of
     * the monitor and its incident could not hold, so it refuses instead of writing on its own.
     */
    @Test
    void refusesAnEventPublishedOutsideATransaction() {
        Monitor monitor = newMonitor();

        assertThatThrownBy(() -> publisher.publishEvent(wentDown(monitor, now(), FailureReason.TIMEOUT, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(incidentsOf(monitor)).isZero();
    }

    private IncidentOpened open(Scenario scenario, Monitor monitor, Instant at) {
        return arrived(
                scenario,
                wentDown(monitor, at, FailureReason.TIMEOUT, null),
                IncidentOpened.class,
                event -> event.monitorId().equals(monitor.id())
                        && event.openedAt().equals(at));
    }

    /** Publishes the event and hands back the first one of {@code type} it led to that matches. */
    private static <E> E arrived(Scenario scenario, Object event, Class<E> type, Predicate<E> matching) {
        AtomicReference<E> arrived = new AtomicReference<>();
        scenario.publish(event).andWaitForEventOfType(type).matching(matching).toArriveAndVerify(arrived::set);
        return arrived.get();
    }

    /** Synchronous: done by the time the publication returns. */
    private static void publishInTransaction(Scenario scenario, Object event) {
        scenario.publish(event).andWaitForStateChange(() -> Boolean.TRUE).andVerify(done -> {});
    }

    private static MonitorWentDown wentDown(
            Monitor monitor, Instant at, FailureReason cause, @Nullable Integer httpStatus) {
        return new MonitorWentDown(
                monitor.id(), monitor.organization(), monitor.project(), "Payments API", at, cause, httpStatus, 3);
    }

    /** PostgreSQL keeps microseconds: an event with nanoseconds would not compare equal to what it stored. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Map<String, Object> incidentRow(UUID incident) {
        return jdbc.queryForMap("SELECT * FROM incidents WHERE id = ?", incident);
    }

    private List<String> timelineOf(UUID incident) {
        return jdbc.queryForList(
                "SELECT type FROM incident_timeline WHERE incident_id = ? ORDER BY occurred_at, type DESC",
                String.class,
                incident);
    }

    private long incidentsOf(Monitor monitor) {
        Long found =
                jdbc.queryForObject("SELECT count(*) FROM incidents WHERE monitor_id = ?", Long.class, monitor.id());
        return found == null ? 0 : found;
    }

    private long activeIncidentsOf(Monitor monitor) {
        Long found = jdbc.queryForObject(
                "SELECT count(*) FROM incidents WHERE monitor_id = ? AND status <> 'RESOLVED'",
                Long.class,
                monitor.id());
        return found == null ? 0 : found;
    }

    /** No row of {@code monitor_state}: nothing schedules it, and no engine of another test checks it. */
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

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", id, uniqueEmail());
        return id;
    }

    private record Monitor(UUID id, UUID organization, UUID project) {}
}
