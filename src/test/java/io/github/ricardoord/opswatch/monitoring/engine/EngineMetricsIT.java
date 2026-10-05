package io.github.ricardoord.opswatch.monitoring.engine;

import static io.github.ricardoord.opswatch.monitoring.engine.PastSchedule.EPOCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.monitoring.application.CheckResultRecorder;
import io.github.ricardoord.opswatch.monitoring.application.MonitorHeaders;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorRepository;
import io.github.ricardoord.opswatch.shared.time.MutableClock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The metrics of the engine (docs/devops/observability.md#métricas-propias) after real checks, with a fake client and
 * the rest real, as Prometheus would read them; and that no metric of the application carries a tag that identifies
 * a customer or a target.
 */
@IntegrationTest
class EngineMetricsIT {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> IDENTIFYING_TAGS = Set.of(
            "monitorId",
            "monitor.id",
            "monitor",
            "organizationId",
            "organization.id",
            "organization",
            "projectId",
            "userId",
            "user.id",
            "url",
            "host",
            "target");

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
    private MeterRegistry meters;

    @Autowired
    private PrometheusMeterRegistry prometheus;

    @Autowired
    private OverdueChecks overdueChecks;

    @Autowired
    private MonitoringEngineProperties properties;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final MutableClock clock = new MutableClock(EPOCH.plusSeconds(3));
    private PastSchedule past;
    private CheckDispatcher dispatcher;

    @BeforeEach
    void clearThePast() {
        past = new PastSchedule(jdbc);
        past.clear();
    }

    @AfterEach
    void stopEverything() {
        if (dispatcher != null) {
            dispatcher.destroy();
        }
        past.clear();
    }

    @Test
    void checksLeaveTheirLagDurationAndClaimInBuckets() {
        past.monitorDueAt(EPOCH);
        past.monitorDueAt(EPOCH.plusSeconds(1));
        long lags = count(EngineMetrics.CHECK_LAG);
        long durations = count(EngineMetrics.CHECK_DURATION, "outcome", "UP");
        long claims = count(EngineMetrics.CLAIM_DURATION);
        dispatcher = dispatcher(10, request -> HEALTHY);

        assertThat(dispatcher.dispatch()).isEqualTo(2);
        await().atMost(Duration.ofSeconds(10)).until(() -> dispatcher.inFlight() == 0);

        assertThat(count(EngineMetrics.CHECK_LAG)).isEqualTo(lags + 2);
        assertThat(count(EngineMetrics.CHECK_DURATION, "outcome", "UP")).isEqualTo(durations + 2);
        assertThat(count(EngineMetrics.CLAIM_DURATION)).isGreaterThan(claims);
        // Late by 3 s and by 2 s: both in the bucket of 5 s, neither in that of 1 s
        assertThat(bucket(EngineMetrics.CHECK_LAG, Duration.ofSeconds(5))
                        - bucket(EngineMetrics.CHECK_LAG, Duration.ofSeconds(1)))
                .isGreaterThanOrEqualTo(2);
        assertThat(boundaries(EngineMetrics.CHECK_LAG))
                .containsExactly(0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0);
        assertThat(boundaries(EngineMetrics.CHECK_DURATION, "outcome", "UP"))
                .containsExactly(0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0);
    }

    @Test
    void anErrorOfTheRequestIsTimedAsAnError() {
        past.monitorDueAt(EPOCH);
        long errors = count(EngineMetrics.CHECK_DURATION, "outcome", EngineMetrics.ERROR);
        dispatcher = dispatcher(10, request -> {
            throw new IllegalStateException("A bug in the client");
        });

        dispatcher.dispatch();
        await().atMost(Duration.ofSeconds(10)).until(() -> dispatcher.inFlight() == 0);

        assertThat(count(EngineMetrics.CHECK_DURATION, "outcome", EngineMetrics.ERROR))
                .isEqualTo(errors + 1);
    }

    /** The permits in use while it runs; with none free, each dispatch counts as saturated. */
    @Test
    void showsTheChecksInFlightAndCountsTheDispatchesWithNoPermitFree() throws Exception {
        past.monitorDueAt(EPOCH);
        past.monitorDueAt(EPOCH.plusSeconds(1));
        CountDownLatch release = new CountDownLatch(1);
        dispatcher = dispatcher(1, request -> {
            awaitQuietly(release);
            return HEALTHY;
        });
        double saturated = meters.counter(EngineMetrics.DISPATCHER_SATURATED).count();

        dispatcher.dispatch();
        assertThat(meters.get(EngineMetrics.CHECKS_IN_FLIGHT).gauge().value()).isEqualTo(1);
        dispatcher.dispatch();
        dispatcher.dispatch();

        assertThat(meters.counter(EngineMetrics.DISPATCHER_SATURATED).count()).isEqualTo(saturated + 2);
        release.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> dispatcher.inFlight() == 0);
        assertThat(meters.get(EngineMetrics.CHECKS_IN_FLIGHT).gauge().value()).isZero();

        dispatcher.destroy();
        assertThat(meters.find(EngineMetrics.CHECKS_IN_FLIGHT).gauge())
                .as("gone with the dispatcher")
                .isNull();
    }

    /** Counted against the clock, so the test sees only its own monitors in 2001. */
    @Test
    void countsTheMonitorsOverdueBeyondTheThreshold() {
        past.monitorDueAt(EPOCH);
        past.monitorDueAt(EPOCH.plusSeconds(56));
        past.monitorDueAt(EPOCH.plusSeconds(58));
        OverdueChecks overdue =
                new OverdueChecks(jdbcClient, properties, meters, Clock.fixed(EPOCH.plusSeconds(60), ZoneOffset.UTC));

        // Due for 60 s, 4 s and 2 s: overdue only beyond 5 s
        assertThat(overdue.count()).isOne();
    }

    /** What Prometheus scrapes: the names of the catalog, with their buckets. */
    @Test
    void prometheusReadsTheMetricsOfTheCatalog() {
        past.monitorDueAt(EPOCH);
        dispatcher = dispatcher(10, request -> HEALTHY);
        dispatcher.dispatch();
        await().atMost(Duration.ofSeconds(10)).until(() -> dispatcher.inFlight() == 0);
        overdueChecks.count();

        String scrape = prometheus.scrape();

        assertThat(scrape)
                .contains("opswatch_monitor_checks_total{outcome=\"UP\",reason=\"NONE\"}")
                .contains("opswatch_monitor_check_duration_seconds_bucket{outcome=\"UP\",le=\"0.05\"}")
                .contains("opswatch_monitor_check_lag_seconds_bucket{le=\"60.0\"}")
                .contains("opswatch_scheduler_claim_duration_seconds_bucket{le=\"0.05\"}")
                .contains("opswatch_monitor_checks_in_flight ")
                .contains("opswatch_monitor_checks_overdue ")
                .contains("opswatch_scheduler_dispatcher_saturated_total")
                .contains("opswatch_event_publications_incomplete ");
    }

    /**
     * With thousands of monitors, a tag per monitor, organization or URL would make Prometheus useless and leak who
     * the customers are. Over every metric of the application, after checks and requests to the API.
     */
    @Test
    void noMetricCarriesATagThatIdentifiesACustomerOrATarget() {
        past.monitorDueAt(EPOCH);
        dispatcher = dispatcher(10, request -> HEALTHY);
        dispatcher.dispatch();
        await().atMost(Duration.ofSeconds(10)).until(() -> dispatcher.inFlight() == 0);
        mvc.get()
                .uri("/api/v1/monitors/" + java.util.UUID.randomUUID() + "/checks")
                .exchange();

        List<Meter> all = meters.getMeters();
        assertThat(all).extracting(meter -> meter.getId().getName()).contains("opswatch.monitor.checks");
        for (Meter meter : all) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(tag.getKey())
                        .as("tag of %s", meter.getId().getName())
                        .isNotIn(IDENTIFYING_TAGS);
                assertThat(UUID.matcher(tag.getValue()).find())
                        .as(
                                "%s=%s of %s",
                                tag.getKey(), tag.getValue(), meter.getId().getName())
                        .isFalse();
                assertThat(tag.getValue())
                        .as("%s of %s", tag.getKey(), meter.getId().getName())
                        .doesNotContain("://")
                        .doesNotContain(PastSchedule.URL);
            }
        }
    }

    private CheckDispatcher dispatcher(int maxConcurrentChecks, HttpMonitorClient client) {
        CheckClaimer claimer = new CheckClaimer(jdbcClient, monitors, headers, recorder, transactions, meters, clock);
        MonitoringEngineProperties engine = new MonitoringEngineProperties(
                true,
                Duration.ofSeconds(1),
                maxConcurrentChecks,
                500,
                5,
                Duration.ofMillis(200),
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                "OpsWatch-Test/0");
        CheckDispatcher started = new CheckDispatcher(claimer, client, recorder, engine, meters, clock);
        started.start();
        return started;
    }

    private long count(String timer, String... tags) {
        Timer found = meters.find(timer).tags(tags).timer();
        return found == null ? 0 : found.count();
    }

    private double bucket(String timer, Duration upTo) {
        return Arrays.stream(meters.get(timer).timer().takeSnapshot().histogramCounts())
                .filter(bucket -> bucket.bucket(TimeUnit.SECONDS) == upTo.toMillis() / 1000.0)
                .mapToDouble(CountAtBucket::count)
                .findFirst()
                .orElseThrow();
    }

    private List<Double> boundaries(String timer, String... tags) {
        return Arrays.stream(meters.get(timer).tags(tags).timer().takeSnapshot().histogramCounts())
                .map(bucket -> bucket.bucket(TimeUnit.SECONDS))
                .toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
