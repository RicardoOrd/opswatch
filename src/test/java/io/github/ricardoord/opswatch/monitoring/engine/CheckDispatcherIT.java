package io.github.ricardoord.opswatch.monitoring.engine;

import static io.github.ricardoord.opswatch.monitoring.engine.PastSchedule.EPOCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.application.CheckResultRecorder;
import io.github.ricardoord.opswatch.monitoring.application.MonitorHeaders;
import io.github.ricardoord.opswatch.monitoring.domain.CheckStatus;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorState;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStatus;
import io.github.ricardoord.opswatch.shared.time.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The dispatcher with a fake {@link HttpMonitorClient} and the rest real: the claim, the recorder and PostgreSQL. Each
 * test builds its own, with a clock in 2001 ({@link PastSchedule}), and calls {@link CheckDispatcher#dispatch()}
 * itself: the shared context of the tests runs no engine.
 */
@IntegrationTest
class CheckDispatcherIT {

    private static final Duration DEADLINE_GRACE = Duration.ofMillis(200);
    private static final Duration SHUTDOWN_GRACE = Duration.ofMillis(100);
    private static final Duration SETTLED = Duration.ofSeconds(10);

    private static final HttpObservation HEALTHY = new HttpObservation.Response(200, Duration.ofMillis(80), 0);

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MonitorRepository monitors;

    @Autowired
    private MonitorHeaders headers;

    @Autowired
    private CheckResultRecorder recorder;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MonitorStateRepository states;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    private final MutableClock clock = new MutableClock(EPOCH.plusSeconds(5));
    private final List<CheckDispatcher> dispatchers = new ArrayList<>();
    private PastSchedule past;

    @BeforeEach
    void clearThePast() {
        past = new PastSchedule(jdbc);
        past.clear();
    }

    @AfterEach
    void stopEverything() {
        dispatchers.forEach(CheckDispatcher::destroy);
        past.clear();
    }

    /** Otherwise it would make real requests for every monitor the tests create. */
    @Test
    void theSharedContextOfTheTestsRunsNoEngine() {
        assertThat(context.getBeanProvider(CheckDispatcher.class).getIfAvailable())
                .isNull();
    }

    @Test
    void runsTheChecksThatAreDueAndRecordsWhatTheySaw() {
        UUID first = past.monitorDueAt(EPOCH);
        UUID second = past.monitorDueAt(EPOCH.plusSeconds(1));
        CheckDispatcher dispatcher =
                dispatcher(10, request -> new HttpObservation.Response(503, Duration.ofMillis(120), 0));

        assertThat(dispatcher.dispatch()).isEqualTo(2);
        awaitIdle(dispatcher);

        Map<String, Object> check = jdbc.queryForMap("SELECT * FROM monitor_checks WHERE monitor_id = ?", first);
        assertThat(check)
                .containsEntry("status", "DOWN")
                .containsEntry("http_status", 503)
                .containsEntry("response_time_ms", 120)
                .containsEntry("failure_reason", "UNEXPECTED_STATUS");
        assertThat(past.checksOf(second)).isOne();
        MonitorState state = states.findById(first).orElseThrow();
        assertThat(state.lastCheckedAt()).isEqualTo(clock.instant());
        assertThat(state.lastCheckStatus()).isEqualTo(CheckStatus.DOWN);
        assertThat(state.consecutiveFailures()).isOne();
    }

    /** It claims only what it can start, so with no permit free the monitors due wait in the database. */
    @Test
    void claimsNoMoreThanItHasPermitsFreeAndNothingWithNoneFree() throws Exception {
        UUID first = past.monitorDueAt(EPOCH);
        UUID second = past.monitorDueAt(EPOCH.plusSeconds(1));
        CountDownLatch release = new CountDownLatch(1);
        CheckDispatcher dispatcher = dispatcher(1, request -> {
            awaitQuietly(release);
            return HEALTHY;
        });

        assertThat(dispatcher.dispatch()).isOne();
        assertThat(past.nextCheckAt(second)).isEqualTo(EPOCH.plusSeconds(1));

        assertThat(dispatcher.dispatch()).isZero();
        assertThat(past.nextCheckAt(second)).isEqualTo(EPOCH.plusSeconds(1));

        release.countDown();
        awaitIdle(dispatcher);
        assertThat(past.checksOf(first)).isOne();
        assertThat(dispatcher.dispatch()).isOne();
        awaitIdle(dispatcher);
        assertThat(past.checksOf(second)).isOne();
    }

    /** An exception is a bug of OpsWatch: blaming the target would open a false incident. */
    @Test
    void anExceptionOfTheRequestIsAnErrorOfOpsWatchNotACheck() {
        UUID monitor = past.monitorDueAt(EPOCH);
        double errors = errors();
        CheckDispatcher dispatcher = dispatcher(10, request -> {
            throw new IllegalStateException("A bug in the client");
        });

        assertThat(dispatcher.dispatch()).isOne();
        awaitIdle(dispatcher);

        assertThat(past.checksOf(monitor)).isZero();
        MonitorState state = states.findById(monitor).orElseThrow();
        assertThat(state.status()).isEqualTo(MonitorStatus.PENDING);
        assertThat(state.lastCheckedAt()).isNull();
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(errors()).isEqualTo(errors + 1);
    }

    @Test
    void onShutdownItStopsClaimingAndWaitsForTheChecksInFlight() throws Exception {
        UUID inFlight = past.monitorDueAt(EPOCH);
        CountDownLatch probing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CheckDispatcher dispatcher = dispatcher(10, request -> {
            probing.countDown();
            awaitQuietly(release);
            return HEALTHY;
        });
        assertThat(dispatcher.dispatch()).isOne();
        assertThat(probing.await(10, TimeUnit.SECONDS)).isTrue();
        UUID later = past.monitorDueAt(EPOCH.plusSeconds(2));

        CountDownLatch stopped = new CountDownLatch(1);
        dispatcher.stop(stopped::countDown);

        assertThat(dispatcher.isRunning()).isFalse();
        assertThat(stopped.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(dispatcher.dispatch()).isZero();
        assertThat(past.nextCheckAt(later)).isEqualTo(EPOCH.plusSeconds(2));

        release.countDown();
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(past.checksOf(inFlight)).isOne();
    }

    /**
     * Waited for until its deadline plus the grace, and then dropped: the client closes on shutdown, and the failure
     * that closing causes is not the target's.
     */
    @Test
    void aCheckStillInFlightAfterItsDeadlineAndTheGraceIsAbandonedUnrecorded() throws Exception {
        UUID slow = past.monitorDueAt(EPOCH, 60, 1_000);
        CountDownLatch probing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CheckDispatcher dispatcher = dispatcher(10, request -> {
            probing.countDown();
            awaitQuietly(release);
            // What the request would see once the client closes under it
            return new HttpObservation.Failure(
                    FailureReason.CONNECTION_FAILED, Duration.ofSeconds(2), "could not connect");
        });
        double errors = errors();
        long dispatchedAt = System.nanoTime();
        assertThat(dispatcher.dispatch()).isOne();
        assertThat(probing.await(10, TimeUnit.SECONDS)).isTrue();

        CountDownLatch stopped = new CountDownLatch(1);
        dispatcher.stop(stopped::countDown);
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        Duration waited = Duration.ofNanos(System.nanoTime() - dispatchedAt);

        release.countDown();
        awaitIdle(dispatcher);
        assertThat(waited)
                .isGreaterThanOrEqualTo(
                        Duration.ofSeconds(1).plus(DEADLINE_GRACE).plus(SHUTDOWN_GRACE));
        assertThat(past.checksOf(slow)).isZero();
        assertThat(errors()).isEqualTo(errors);
    }

    private CheckDispatcher dispatcher(int maxConcurrentChecks, HttpMonitorClient client) {
        CheckClaimer claimer = new CheckClaimer(jdbcClient, monitors, headers, recorder, transactions, clock);
        MonitoringEngineProperties properties = new MonitoringEngineProperties(
                true,
                Duration.ofSeconds(1),
                maxConcurrentChecks,
                500,
                5,
                DEADLINE_GRACE,
                SHUTDOWN_GRACE,
                "OpsWatch-Test/0");
        CheckDispatcher dispatcher = new CheckDispatcher(claimer, client, recorder, properties, clock);
        dispatcher.start();
        dispatchers.add(dispatcher);
        return dispatcher;
    }

    private static void awaitIdle(CheckDispatcher dispatcher) {
        await().atMost(SETTLED).until(() -> dispatcher.inFlight() == 0);
    }

    private double errors() {
        return meters.counter("opswatch.monitor.checks", "outcome", "ERROR", "reason", "NONE")
                .count();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
