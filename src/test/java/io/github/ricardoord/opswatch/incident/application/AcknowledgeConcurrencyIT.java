package io.github.ricardoord.opswatch.incident.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.incident.Resolution;
import io.github.ricardoord.opswatch.incident.domain.Incident;
import io.github.ricardoord.opswatch.incident.domain.IncidentRepository;
import io.github.ricardoord.opswatch.incident.domain.IncidentStatus;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.application.CheckResultRecorder;
import io.github.ricardoord.opswatch.monitoring.application.MonitorService;
import io.github.ricardoord.opswatch.monitoring.application.SettingsChanges;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSnapshot;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStatus;
import io.github.ricardoord.opswatch.shared.error.BusinessRuleViolationException;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An acknowledgement and the recovery of the same incident at the same time
 * (docs/architecture/incident-lifecycle.md#6-concurrencia-y-casos-límite): both lock the row of the incident, so
 * whichever comes second waits for the first. Neither fails on a version, and the check is always kept.
 */
@IntegrationTest
class AcknowledgeConcurrencyIT {

    /** How long a blocked operation is given to show it is really waiting. */
    private static final Duration STILL_WAITING = Duration.ofMillis(500);

    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";
    /** A failure threshold of 3 and a recovery threshold of 2. */
    private static final SettingsChanges DEFAULTS =
            new SettingsChanges(null, null, null, null, null, PatchField.absent(), null, null, null);

    private static final CheckOutcome TIMED_OUT =
            CheckOutcome.down(FailureReason.TIMEOUT, null, null, "no response within the timeout");
    private static final CheckOutcome HEALTHY = CheckOutcome.up(200, Duration.ofMillis(143));

    @Autowired
    private IncidentService service;

    @Autowired
    private IncidentRepository incidents;

    @Autowired
    private IncidentLifecycle lifecycle;

    @Autowired
    private CheckResultRecorder recorder;

    @Autowired
    private MonitorService monitors;

    @Autowired
    private MonitorStateRepository states;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    /**
     * The recovery holds the row when the acknowledgement arrives: it waits, then finds the incident resolved and gets
     * the 409 of the rule. Without the lock it would read the incident as {@code OPEN} and fail later on its version.
     */
    @Test
    void anAcknowledgementDuringTheRecoveryWaitsForItAndThenFindsItResolved() throws Exception {
        Fixture fixture = newFixture();
        Incident open = activeIncidentOf(fixture.monitor());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> recovering = threads.submit(() -> transactions.executeWithoutResult(transaction -> {
                UUID monitorId = fixture.monitor().monitorId();
                incidents.findActiveByMonitorIdForUpdate(monitorId).orElseThrow();
                locked.countDown();
                awaitQuietly(release);
                lifecycle.resolve(
                        monitorId, Resolution.AUTO_RECOVERED, open.openedAt().plusSeconds(60), null);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<IncidentDetail> acknowledging =
                    threads.submit(() -> service.acknowledge(fixture.owner(), open.id(), "Looking into it"));

            assertThatThrownBy(() -> acknowledging.get(STILL_WAITING.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            recovering.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> acknowledging.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(BusinessRuleViolationException.class);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }

        assertThat(incidents.findById(open.id()).orElseThrow().status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(timelineOf(open.id())).containsExactly("OPENED", "RESOLVED");
    }

    /**
     * The acknowledgement holds the row when the check that recovers the monitor is recorded: the check waits, then
     * resolves the incident that was acknowledged. Nothing is lost: the check, the new state and the resolution.
     */
    @Test
    void aRecoveryDuringAnAcknowledgementWaitsForItAndThenResolvesTheIncident() throws Exception {
        Fixture fixture = newFixture();
        MonitorSnapshot monitor = fixture.monitor();
        Incident open = activeIncidentOf(monitor);
        Instant start = clock.instant().plusSeconds(10);
        recorder.record(monitor, start, HEALTHY);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> acknowledging = threads.submit(() -> transactions.executeWithoutResult(transaction -> {
                Incident incident = incidents.findByIdForUpdate(open.id()).orElseThrow();
                locked.countDown();
                awaitQuietly(release);
                incident.acknowledge(fixture.owner(), clock);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> recording = threads.submit(() -> recorder.record(monitor, start.plusSeconds(1), HEALTHY));

            assertThatThrownBy(() -> recording.get(STILL_WAITING.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            acknowledging.get(10, TimeUnit.SECONDS);
            recording.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }

        Incident resolved = incidents.findById(open.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.acknowledgedBy()).isEqualTo(fixture.owner());
        assertThat(resolved.resolution()).isEqualTo(Resolution.AUTO_RECOVERED);
        assertThat(states.findById(monitor.monitorId()).orElseThrow().status()).isEqualTo(MonitorStatus.UP);
        assertThat(checksOf(monitor)).isEqualTo(5);
    }

    private Incident activeIncidentOf(MonitorSnapshot monitor) {
        return transactions.execute(transaction ->
                incidents.findActiveByMonitorIdForUpdate(monitor.monitorId()).orElseThrow());
    }

    private List<String> timelineOf(UUID incident) {
        return jdbc.queryForList(
                "SELECT type FROM incident_timeline WHERE incident_id = ? ORDER BY occurred_at, id",
                String.class,
                incident);
    }

    private long checksOf(MonitorSnapshot monitor) {
        Long checks = jdbc.queryForObject(
                "SELECT count(*) FROM monitor_checks WHERE monitor_id = ?", Long.class, monitor.monitorId());
        return checks == null ? 0 : checks;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** A monitor that is down, with its incident open: three failed checks. */
    private Fixture newFixture() {
        UUID owner = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", owner, uniqueEmail());
        UUID organization = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                organization);
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, 'OWNER', now(), now())""", organization, owner);
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, 'Production', now(), now())""", project, organization);
        MonitorSnapshot monitor =
                MonitorSnapshot.of(monitors.create(owner, project, "Payments API", HEALTH, DEFAULTS, List.of())
                        .monitor());
        Instant start = clock.instant();
        for (int failure = 1; failure <= 3; failure++) {
            recorder.record(monitor, start.plusSeconds(failure), TIMED_OUT);
        }
        return new Fixture(owner, monitor);
    }

    private record Fixture(UUID owner, MonitorSnapshot monitor) {}
}
