package io.github.ricardoord.opswatch.monitoring.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.MonitorRecovered;
import io.github.ricardoord.opswatch.monitoring.MonitorWentDown;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.CheckStatus;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSnapshot;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorState;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStatus;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The results of checks against PostgreSQL: what is kept, how the state moves, what is announced, and what a pause, a
 * deletion or an error of OpsWatch do to a result that arrives meanwhile.
 */
@IntegrationTest
@RecordApplicationEvents
class CheckResultRecorderIT {

    private static final int RACES = 50;
    /** How long a blocked result is given to show it is really waiting. */
    private static final Duration STILL_WAITING = Duration.ofMillis(500);

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
    private MonitorService service;

    @Autowired
    private MonitorStateRepository states;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationEvents events;

    @Test
    void threeFailuresTakeTheMonitorDownOnceAndTwoSuccessesBringItBack() {
        UUID owner = newUser();
        MonitorSnapshot monitor = newMonitor(owner);
        Instant start = clock.instant().truncatedTo(ChronoUnit.MICROS);

        recorder.record(monitor, start.plusSeconds(1), TIMED_OUT);
        recorder.record(monitor, start.plusSeconds(2), TIMED_OUT);
        assertThat(stateOf(monitor).status()).isEqualTo(MonitorStatus.PENDING);
        recorder.record(monitor, start.plusSeconds(3), TIMED_OUT);
        recorder.record(monitor, start.plusSeconds(4), TIMED_OUT);

        assertThat(stateOf(monitor).status()).isEqualTo(MonitorStatus.DOWN);
        assertThat(wentDown(monitor))
                .containsExactly(new MonitorWentDown(
                        monitor.monitorId(),
                        monitor.organizationId(),
                        monitor.projectId(),
                        monitor.name(),
                        start.plusSeconds(3),
                        FailureReason.TIMEOUT,
                        null,
                        3));

        recorder.record(monitor, start.plusSeconds(5), HEALTHY);
        assertThat(stateOf(monitor).status()).isEqualTo(MonitorStatus.DOWN);
        recorder.record(monitor, start.plusSeconds(6), HEALTHY);

        MonitorState state = stateOf(monitor);
        assertThat(state.status()).isEqualTo(MonitorStatus.UP);
        assertThat(state.statusChangedAt()).isEqualTo(start.plusSeconds(6));
        assertThat(recovered(monitor))
                .containsExactly(new MonitorRecovered(
                        monitor.monitorId(),
                        monitor.organizationId(),
                        monitor.projectId(),
                        monitor.name(),
                        start.plusSeconds(6),
                        start.plusSeconds(3)));
        assertThat(checksOf(monitor)).isEqualTo(6);
    }

    @Test
    void keepsTheCheckAndItsResultAsTheLastOne() {
        MonitorSnapshot monitor = newMonitor(newUser());
        Instant checkedAt = clock.instant().plusSeconds(1);
        CheckOutcome outcome =
                CheckOutcome.down(FailureReason.UNEXPECTED_STATUS, 503, Duration.ofNanos(143_999_999), null);

        recorder.record(monitor, checkedAt, outcome);

        Map<String, Object> row =
                jdbc.queryForMap("SELECT * FROM monitor_checks WHERE monitor_id = ?", monitor.monitorId());
        assertThat(row)
                .containsEntry("status", "DOWN")
                .containsEntry("http_status", 503)
                .containsEntry("response_time_ms", 143)
                .containsEntry("failure_reason", "UNEXPECTED_STATUS")
                .containsEntry("error_detail", null);
        MonitorState state = stateOf(monitor);
        assertThat(state.lastCheckedAt()).isEqualTo(checkedAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(state.lastCheckStatus()).isEqualTo(CheckStatus.DOWN);
        assertThat(state.lastHttpStatus()).isEqualTo(503);
        assertThat(state.lastResponseTimeMs()).isEqualTo(143);
        assertThat(state.lastFailureReason()).isEqualTo(FailureReason.UNEXPECTED_STATUS);
        assertThat(state.consecutiveFailures()).isEqualTo(1);
    }

    /** Whichever takes the row of the state first, the monitor ends paused and the check is kept. */
    @Test
    void aPauseAgainstTheResultOfACheckAlwaysEndsPaused() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            for (int race = 0; race < RACES; race++) {
                UUID owner = newUser();
                MonitorSnapshot monitor = newMonitor(owner);
                Instant startedAt = clock.instant();
                CountDownLatch go = new CountDownLatch(1);

                Future<?> pause = threads.submit(() -> {
                    go.await();
                    return service.pause(owner, monitor.monitorId());
                });
                Future<?> result = threads.submit(() -> {
                    go.await();
                    recorder.record(monitor, startedAt, TIMED_OUT);
                    return null;
                });
                go.countDown();
                pause.get(10, TimeUnit.SECONDS);
                result.get(10, TimeUnit.SECONDS);

                MonitorState state = stateOf(monitor);
                assertThat(state.status()).as("race %d", race).isEqualTo(MonitorStatus.PAUSED);
                assertThat(state.nextCheckAt()).as("race %d", race).isNull();
                assertThat(checksOf(monitor)).as("race %d", race).isEqualTo(1);
            }
        } finally {
            threads.shutdownNow();
        }
    }

    /**
     * The interleaving that the race above rarely hits: the pause holds the row of the state when the result arrives.
     * Without the lock, the result would read the state as it was before the pause, wait only to write it, and then
     * write it back over the pause.
     */
    @Test
    void aResultThatArrivesDuringAPauseWaitsForItAndChangesNothing() throws Exception {
        MonitorSnapshot monitor = newMonitor(newUser());
        Instant startedAt = clock.instant();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> pausing = threads.submit(() -> transactions.executeWithoutResult(transaction -> {
                states.findByIdForUpdate(monitor.monitorId()).orElseThrow().pause(clock);
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> recording = threads.submit(() -> recorder.record(monitor, startedAt, TIMED_OUT));

            assertThatThrownBy(() -> recording.get(STILL_WAITING.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            pausing.get(10, TimeUnit.SECONDS);
            recording.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }

        MonitorState state = stateOf(monitor);
        assertThat(state.status()).isEqualTo(MonitorStatus.PAUSED);
        assertThat(state.nextCheckAt()).isNull();
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(checksOf(monitor)).isEqualTo(1);
    }

    @Test
    void theResultOfAMonitorDeletedWhileItsCheckWasInFlightIsKeptAndChangesNothing() {
        UUID owner = newUser();
        MonitorSnapshot monitor = newMonitor(owner);
        Instant startedAt = clock.instant();
        service.delete(owner, monitor.monitorId());

        recorder.record(monitor, startedAt, TIMED_OUT);

        MonitorState state = stateOf(monitor);
        assertThat(state.status()).isEqualTo(MonitorStatus.PAUSED);
        assertThat(state.nextCheckAt()).isNull();
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(checksOf(monitor)).isEqualTo(1);
    }

    /** It belongs to the monitor before the resume, which has not been checked since. */
    @Test
    void theResultOfACheckInFlightAcrossAPauseAndAResumeChangesNothing() {
        UUID owner = newUser();
        MonitorSnapshot monitor = newMonitor(owner);
        Instant startedAt = clock.instant();
        service.pause(owner, monitor.monitorId());
        service.resume(owner, monitor.monitorId());

        recorder.record(monitor, startedAt, TIMED_OUT);

        MonitorState state = stateOf(monitor);
        assertThat(state.status()).isEqualTo(MonitorStatus.PENDING);
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.lastCheckedAt()).isNull();
        assertThat(checksOf(monitor)).isEqualTo(1);
    }

    /**
     * A listener of {@code incident} that throws rolls back the check that took the monitor down: no check, no
     * transition, an {@code ERROR} in the metric, and the next check evaluates again.
     */
    @Test
    void anErrorOfOpsWatchKeepsNoCheckAndChangesNoState() {
        MonitorSnapshot monitor = newMonitor(newUser());
        Instant start = clock.instant();
        recorder.record(monitor, start.plusSeconds(1), TIMED_OUT);
        recorder.record(monitor, start.plusSeconds(2), TIMED_OUT);
        double errors = errors();
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

        assertThat(errors()).isEqualTo(errors + 1);
        assertThat(checksOf(monitor)).isEqualTo(2);
        assertThat(stateOf(monitor).status()).isEqualTo(MonitorStatus.PENDING);
        assertThat(stateOf(monitor).consecutiveFailures()).isEqualTo(2);

        recorder.record(monitor, start.plusSeconds(4), TIMED_OUT);

        assertThat(stateOf(monitor).status()).isEqualTo(MonitorStatus.DOWN);
        assertThat(checksOf(monitor)).isEqualTo(3);
    }

    /**
     * A check in flight when its monitor was deleted, that comes back after the retention purged the state: there is
     * nothing to keep it against, and nothing went wrong (OW-029).
     */
    @Test
    void aResultForAStateAlreadyPurgedIsDroppedWithoutAnError() {
        UUID owner = newUser();
        MonitorSnapshot monitor = newMonitor(owner);
        service.delete(owner, monitor.monitorId());
        jdbc.update("DELETE FROM monitor_state WHERE monitor_id = ?", monitor.monitorId());
        double errors = errors();
        double timeouts = counted("DOWN", "TIMEOUT");

        recorder.record(monitor, clock.instant(), TIMED_OUT);

        assertThat(checksOf(monitor)).isZero();
        assertThat(errors()).isEqualTo(errors);
        assertThat(counted("DOWN", "TIMEOUT"))
                .as("not counted as a check either")
                .isEqualTo(timeouts);
    }

    @Test
    void countsEveryResultByOutcomeAndReason() {
        MonitorSnapshot monitor = newMonitor(newUser());
        double timeouts = counted("DOWN", "TIMEOUT");
        double healthy = counted("UP", "NONE");

        recorder.record(monitor, clock.instant().plusSeconds(1), TIMED_OUT);
        recorder.record(monitor, clock.instant().plusSeconds(2), HEALTHY);

        assertThat(counted("DOWN", "TIMEOUT")).isEqualTo(timeouts + 1);
        assertThat(counted("UP", "NONE")).isEqualTo(healthy + 1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private double errors() {
        return counted(CheckResultRecorder.ERROR, CheckResultRecorder.NO_REASON);
    }

    private double counted(String outcome, String reason) {
        return meters.counter(CheckResultRecorder.CHECKS, "outcome", outcome, "reason", reason)
                .count();
    }

    private List<MonitorWentDown> wentDown(MonitorSnapshot monitor) {
        return events.stream(MonitorWentDown.class)
                .filter(event -> event.monitorId().equals(monitor.monitorId()))
                .toList();
    }

    private List<MonitorRecovered> recovered(MonitorSnapshot monitor) {
        return events.stream(MonitorRecovered.class)
                .filter(event -> event.monitorId().equals(monitor.monitorId()))
                .toList();
    }

    private MonitorState stateOf(MonitorSnapshot monitor) {
        return states.findById(monitor.monitorId()).orElseThrow();
    }

    private long checksOf(MonitorSnapshot monitor) {
        Long checks = jdbc.queryForObject(
                "SELECT count(*) FROM monitor_checks WHERE monitor_id = ?", Long.class, monitor.monitorId());
        return checks == null ? 0 : checks;
    }

    private MonitorSnapshot newMonitor(UUID owner) {
        UUID project = projectOf(organizationOf(owner));
        return MonitorSnapshot.of(service.create(owner, project, "Payments API", HEALTH, DEFAULTS, List.of())
                .monitor());
    }

    private UUID projectOf(UUID organization) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, 'Production', now(), now())""", id, organization);
        return id;
    }

    private UUID organizationOf(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                id);
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, 'OWNER', now(), now())""", id, owner);
        return id;
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", id, uniqueEmail());
        return id;
    }
}
