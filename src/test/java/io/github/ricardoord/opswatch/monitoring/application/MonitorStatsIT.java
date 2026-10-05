package io.github.ricardoord.opswatch.monitoring.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.application.MonitorStatsQueries.WindowStats;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.CheckStats;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorCheckRepository;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The statistics of a monitor against PostgreSQL, with known checks and figures worked out by hand
 * ({@code percentile_cont} interpolates between the two values around the position {@code (n - 1) × p}).
 */
@IntegrationTest
class MonitorStatsIT {

    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";
    private static final SettingsChanges DEFAULTS =
            new SettingsChanges(null, null, null, null, null, PatchField.absent(), null, null, null);

    @Autowired
    private MonitorStatsQueries queries;

    @Autowired
    private MonitorService service;

    @Autowired
    private MonitorCheckRepository checks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    /**
     * Ten checks in the last day. Nine got a response, of 100 to 900 ms: the average and the median are 500 ms, the
     * p95 is at position 7.6 (800 + 0.6 × 100 = 860) and the p99 at 7.92 (892). Eight answered correctly, so the uptime
     * is 80 %.
     */
    @Test
    void sumsUpTheChecksOfTheWindowAsWorkedOutByHand() {
        UUID owner = newUser();
        UUID monitor = newMonitor(owner);
        Instant now = clock.instant();
        int minute = 0;
        for (int ms : List.of(100, 200, 300, 400, 500, 600, 700)) {
            record(monitor, now.minus(Duration.ofMinutes(++minute)), CheckOutcome.up(200, Duration.ofMillis(ms)));
        }
        record(monitor, now.minus(Duration.ofMinutes(++minute)), CheckOutcome.degraded(200, Duration.ofMillis(900)));
        record(
                monitor,
                now.minus(Duration.ofMinutes(++minute)),
                CheckOutcome.down(FailureReason.UNEXPECTED_STATUS, 503, Duration.ofMillis(800), null));
        record(
                monitor,
                now.minus(Duration.ofMinutes(++minute)),
                CheckOutcome.down(FailureReason.TIMEOUT, null, null, "no response within the timeout"));
        // Older than a day: only in the wider windows
        record(
                monitor,
                now.minus(Duration.ofHours(25)),
                CheckOutcome.down(FailureReason.DNS_FAILURE, null, null, null));

        WindowStats day = queries.statsOf(owner, monitor, StatsWindow.LAST_24_HOURS);

        CheckStats stats = day.stats();
        assertThat(stats.total()).isEqualTo(10);
        assertThat(stats.up()).isEqualTo(7);
        assertThat(stats.degraded()).isEqualTo(1);
        assertThat(stats.down()).isEqualTo(2);
        assertThat(stats.uptimePercent()).isEqualByComparingTo(new BigDecimal("80.000"));
        assertThat(stats.responseTimeMs()).isEqualTo(new CheckStats.ResponseTimes(500, 500, 860, 892));
        assertThat(stats.failuresByReason())
                .isEqualTo(Map.of(FailureReason.TIMEOUT, 1L, FailureReason.UNEXPECTED_STATUS, 1L));
        assertThat(day.to()).isAfterOrEqualTo(now.truncatedTo(ChronoUnit.MICROS));
        assertThat(Duration.between(day.from(), day.to())).isEqualTo(Duration.ofHours(24));

        CheckStats week =
                queries.statsOf(owner, monitor, StatsWindow.LAST_7_DAYS).stats();
        assertThat(week.total()).isEqualTo(11);
        assertThat(week.uptimePercent()).isEqualByComparingTo(new BigDecimal("72.727"));
        assertThat(week.failuresByReason()).containsEntry(FailureReason.DNS_FAILURE, 1L);
    }

    /** No checks is no uptime, not 100 % nor 0 %: a monitor paused all along has nothing to show. */
    @Test
    void withNoChecksThereIsNoUptimeNorResponseTimes() {
        UUID owner = newUser();
        UUID monitor = newMonitor(owner);

        CheckStats stats =
                queries.statsOf(owner, monitor, StatsWindow.LAST_30_DAYS).stats();

        assertThat(stats.total()).isZero();
        assertThat(stats.uptimePercent()).isNull();
        assertThat(stats.responseTimeMs()).isEqualTo(new CheckStats.ResponseTimes(null, null, null, null));
        assertThat(stats.failuresByReason()).isEmpty();
    }

    /** Only failures without a response: the counts are there, the response times are not. */
    @Test
    void responseTimesOnlyCountTheChecksThatGotAResponse() {
        UUID owner = newUser();
        UUID monitor = newMonitor(owner);
        Instant now = clock.instant();
        record(monitor, now.minusSeconds(60), CheckOutcome.down(FailureReason.CONNECTION_FAILED, null, null, null));
        record(monitor, now.minusSeconds(120), CheckOutcome.down(FailureReason.CONNECTION_FAILED, null, null, null));
        record(monitor, now.minusSeconds(180), CheckOutcome.up(200, Duration.ofMillis(143)));

        CheckStats stats =
                queries.statsOf(owner, monitor, StatsWindow.LAST_24_HOURS).stats();

        assertThat(stats.uptimePercent()).isEqualByComparingTo(new BigDecimal("33.333"));
        assertThat(stats.responseTimeMs()).isEqualTo(new CheckStats.ResponseTimes(143, 143, 143, 143));
        assertThat(stats.failuresByReason()).isEqualTo(Map.of(FailureReason.CONNECTION_FAILED, 2L));
    }

    /** 30 days of a monitor every 30 s, the most a window holds (docs/database/data-retention.md). */
    @Test
    void thirtyDaysAtThirtySeconds() {
        UUID owner = newUser();
        UUID monitor = newMonitor(owner);
        Instant start = clock.instant().minus(Duration.ofDays(30)).plusSeconds(60);
        jdbc.update("""
                INSERT INTO monitor_checks (monitor_id, checked_at, status, http_status, response_time_ms)
                SELECT ?, ?::timestamptz + make_interval(secs => 30 * n), 'UP', 200, 100 + n % 400
                FROM generate_series(0, 86399) AS n
                WHERE ?::timestamptz + make_interval(secs => 30 * n) < now()""", monitor, start.toString(), start.toString());

        CheckStats stats =
                queries.statsOf(owner, monitor, StatsWindow.LAST_30_DAYS).stats();

        assertThat(stats.total()).isBetween(86_000L, 86_400L);
        assertThat(stats.uptimePercent()).isEqualByComparingTo(new BigDecimal("100.000"));
        assertThat(stats.responseTimeMs().p50()).isBetween(290, 310);
    }

    private void record(UUID monitor, Instant checkedAt, CheckOutcome outcome) {
        checks.insert(monitor, checkedAt.truncatedTo(ChronoUnit.MICROS), outcome);
    }

    private UUID newMonitor(UUID owner) {
        UUID project = projectOf(organizationOf(owner));
        return service.create(owner, project, "Payments API", HEALTH, DEFAULTS, List.of())
                .monitor()
                .id();
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
