package io.github.ricardoord.opswatch.incident.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.MonitorWentDown;
import io.github.ricardoord.opswatch.monitoring.application.CheckResultRecorder;
import io.github.ricardoord.opswatch.monitoring.application.MonitorService;
import io.github.ricardoord.opswatch.monitoring.application.SettingsChanges;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSnapshot;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStatus;
import io.github.ricardoord.opswatch.organization.application.ProjectService;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Incidents driven by the real monitoring module: the results of checks, pauses and deletions open and resolve them in
 * the same transaction that moves the state of the monitor (docs/architecture/incident-lifecycle.md#4-relación-entre-el-estado-del-monitor-y-el-incidente).
 */
@IntegrationTest
class MonitorIncidentsIT {

    private static final Duration CLEANUP = Duration.ofSeconds(10);
    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";
    /** A failure threshold of 3 and a recovery threshold of 2. */
    private static final SettingsChanges DEFAULTS =
            new SettingsChanges(null, null, null, null, null, PatchField.absent(), null, null, null);

    private static final CheckOutcome TIMED_OUT =
            CheckOutcome.down(FailureReason.TIMEOUT, null, null, "no response within the timeout");
    private static final CheckOutcome HEALTHY = CheckOutcome.up(200, Duration.ofMillis(143));

    @Autowired
    private CheckResultRecorder recorder;

    @Autowired
    private MonitorService monitors;

    @Autowired
    private ProjectService projects;

    @Autowired
    private MonitorStateRepository states;

    @Autowired
    private IncidentMetrics metrics;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    /** The example of docs/architecture/incident-lifecycle.md#7-ejemplo-de-timeline: one fall, one incident. */
    @Test
    void aMonitorThatGoesDownAndRecoversHasExactlyOneIncident() {
        Fixture fixture = newFixture();
        MonitorSnapshot monitor = fixture.monitor();
        Instant start = clock.instant().truncatedTo(ChronoUnit.MICROS);

        recorder.record(monitor, start.plusSeconds(1), TIMED_OUT);
        recorder.record(monitor, start.plusSeconds(2), TIMED_OUT);
        assertThat(incidentsOf(monitor)).isEmpty();
        recorder.record(monitor, start.plusSeconds(3), TIMED_OUT);
        recorder.record(monitor, start.plusSeconds(4), TIMED_OUT);
        recorder.record(monitor, start.plusSeconds(5), HEALTHY);

        List<Map<String, Object>> open = incidentsOf(monitor);
        assertThat(open)
                .singleElement()
                .satisfies(incident -> assertThat(incident)
                        .containsEntry("status", "OPEN")
                        .containsEntry("cause", "TIMEOUT")
                        .containsEntry("opened_at", at(start.plusSeconds(3))));

        recorder.record(monitor, start.plusSeconds(6), HEALTHY);

        assertThat(states.findById(monitor.monitorId()).orElseThrow().status()).isEqualTo(MonitorStatus.UP);
        assertThat(incidentsOf(monitor))
                .singleElement()
                .satisfies(incident -> assertThat(incident)
                        .containsEntry("status", "RESOLVED")
                        .containsEntry("resolution", "AUTO_RECOVERED")
                        .containsEntry("resolved_at", at(start.plusSeconds(6))));
    }

    /** Rules R5 and R8: the pause resolves it with who paused, and a fall after resuming is a new incident. */
    @Test
    void pausingADownMonitorResolvesItsIncidentAndTheNextFallOpensANewOne() {
        Fixture fixture = newFixture();
        MonitorSnapshot monitor = fixture.monitor();
        takeDown(monitor, clock.instant());

        monitors.pause(fixture.owner(), monitor.monitorId());

        assertThat(incidentsOf(monitor))
                .singleElement()
                .satisfies(incident -> assertThat(incident)
                        .containsEntry("resolution", "MONITOR_PAUSED")
                        .containsEntry("resolved_by", fixture.owner()));

        monitors.resume(fixture.owner(), monitor.monitorId());
        takeDown(monitor, clock.instant().plusSeconds(60));

        assertThat(incidentsOf(monitor))
                .extracting(incident -> incident.get("status"))
                .containsExactly("RESOLVED", "OPEN");
    }

    /** {@code ProjectDeleted} → the cleanup deletes the monitor → {@code MonitorDeleted} → the incident is resolved. */
    @Test
    void deletingTheProjectOfADownMonitorResolvesItsIncident() {
        Fixture fixture = newFixture();
        MonitorSnapshot monitor = fixture.monitor();
        takeDown(monitor, clock.instant());

        projects.delete(fixture.owner(), monitor.projectId());

        Awaitility.await()
                .atMost(CLEANUP)
                .untilAsserted(() -> assertThat(incidentsOf(monitor))
                        .singleElement()
                        .satisfies(incident -> assertThat(incident)
                                .containsEntry("resolution", "MONITOR_DELETED")
                                .containsEntry("resolved_by", fixture.owner())));
    }

    /**
     * The transition, the check and the incident are one transaction: if anything in it throws, none of them is kept,
     * and the next check opens the incident.
     */
    @Test
    void aCheckRolledBackOpensNoIncidentAndTheNextOneDoes() {
        MonitorSnapshot monitor = newFixture().monitor();
        Instant start = clock.instant();
        recorder.record(monitor, start.plusSeconds(1), TIMED_OUT);
        recorder.record(monitor, start.plusSeconds(2), TIMED_OUT);
        double opened = opened();
        ApplicationListener<ApplicationEvent> failing = event -> {
            if (event instanceof PayloadApplicationEvent<?> payload
                    && payload.getPayload() instanceof MonitorWentDown down
                    && down.monitorId().equals(monitor.monitorId())) {
                throw new IllegalStateException("A bug in a listener");
            }
        };
        context.addApplicationListener(failing);
        try {
            recorder.record(monitor, start.plusSeconds(3), TIMED_OUT);
        } finally {
            context.getBean(
                            AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
                            ApplicationEventMulticaster.class)
                    .removeApplicationListener(failing);
        }

        assertThat(incidentsOf(monitor)).isEmpty();
        assertThat(opened()).as("nothing committed, nothing counted").isEqualTo(opened);

        recorder.record(monitor, start.plusSeconds(4), TIMED_OUT);

        assertThat(incidentsOf(monitor))
                .singleElement()
                .satisfies(incident -> assertThat(incident)
                        .containsEntry("opened_at", at(start.plusSeconds(4).truncatedTo(ChronoUnit.MICROS))));
        assertThat(opened()).isEqualTo(opened + 1);
    }

    @Test
    void theActiveIncidentsAreCountedOnASchedule() {
        takeDown(newFixture().monitor(), clock.instant());

        long counted = metrics.countActive();

        Long active = jdbc.queryForObject("SELECT count(*) FROM incidents WHERE status <> 'RESOLVED'", Long.class);
        assertThat(counted).isPositive().isEqualTo(active);
        assertThat(meters.get(IncidentMetrics.ACTIVE).gauge().value()).isEqualTo(counted);
    }

    private void takeDown(MonitorSnapshot monitor, Instant from) {
        for (int failure = 1; failure <= 3; failure++) {
            recorder.record(monitor, from.plusSeconds(failure), TIMED_OUT);
        }
        assertThat(states.findById(monitor.monitorId()).orElseThrow().status()).isEqualTo(MonitorStatus.DOWN);
    }

    private double opened() {
        return meters.counter(IncidentMetrics.OPENED).count();
    }

    private List<Map<String, Object>> incidentsOf(MonitorSnapshot monitor) {
        return jdbc.queryForList(
                "SELECT * FROM incidents WHERE monitor_id = ? ORDER BY opened_at", monitor.monitorId());
    }

    /** How {@code queryForMap} hands back a {@code timestamptz}. */
    private static java.sql.Timestamp at(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    private Fixture newFixture() {
        UUID owner = newUser();
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
        return new Fixture(owner, monitor);
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", id, uniqueEmail());
        return id;
    }

    private record Fixture(UUID owner, MonitorSnapshot monitor) {}
}
