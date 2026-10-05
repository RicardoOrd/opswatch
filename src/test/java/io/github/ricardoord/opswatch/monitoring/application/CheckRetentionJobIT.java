package io.github.ricardoord.opswatch.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorCheckRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The purge against PostgreSQL. Its checks are from 1995, a past no other test uses: the cutoff falls among them and
 * after nothing else. The steps of the deleted monitors take those of every test, which no test reads again.
 */
@IntegrationTest
class CheckRetentionJobIT {

    private static final Instant NOW = Instant.parse("1995-03-01T00:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final Instant CUTOFF = NOW.minus(RETENTION);
    private static final CheckOutcome HEALTHY = CheckOutcome.up(200, Duration.ofMillis(143));

    @Autowired
    private MonitorCheckRepository checks;

    @Autowired
    private MonitorStateRepository states;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void deletesOnlyTheChecksOlderThanTheRetention() {
        UUID monitor = newMonitor();
        checks.insert(monitor, CUTOFF.minusNanos(1_000), HEALTHY);
        checks.insert(monitor, CUTOFF, HEALTHY);
        checks.insert(monitor, CUTOFF.plus(Duration.ofDays(1)), HEALTHY);

        CheckRetentionJob.Purged purged = job(10_000).purge();

        assertThat(purged.oldChecks()).isEqualTo(1);
        assertThat(checkTimesOf(monitor)).isEqualTo(2);
        assertThat(checkAt(monitor, CUTOFF)).isTrue();
        assertThat(stateExists(monitor)).isTrue();
    }

    /** Whatever their age; then their state; the row of the monitor stays for the incidents that will refer to it. */
    @Test
    void deletesTheChecksOfDeletedMonitorsAndThenTheirStateButKeepsTheMonitor() {
        UUID deleted = newMonitor();
        UUID alive = newMonitor();
        for (UUID monitor : new UUID[] {deleted, alive}) {
            checks.insert(monitor, NOW.minus(Duration.ofDays(1)), HEALTHY);
            checks.insert(monitor, NOW.minus(Duration.ofHours(1)), HEALTHY);
        }
        delete(deleted);

        CheckRetentionJob.Purged purged = job(10_000).purge();

        assertThat(purged.checksOfDeletedMonitors()).isGreaterThanOrEqualTo(2);
        assertThat(purged.statesOfDeletedMonitors()).isGreaterThanOrEqualTo(1);
        assertThat(checkTimesOf(deleted)).isZero();
        assertThat(stateExists(deleted)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitors WHERE id = ?", Long.class, deleted))
                .isOne();
        assertThat(checkTimesOf(alive)).isEqualTo(2);
        assertThat(stateExists(alive)).isTrue();
    }

    @Test
    void theStateOfADeletedMonitorGoesOnlyAfterItsChecks() {
        UUID deleted = newMonitor();
        checks.insert(deleted, NOW.minus(Duration.ofHours(1)), HEALTHY);
        delete(deleted);

        transactions.executeWithoutResult(transaction -> states.deleteOfDeletedMonitorsWithoutChecks(10_000));
        assertThat(stateExists(deleted)).isTrue();

        transactions.executeWithoutResult(transaction -> checks.deleteOfDeletedMonitors(10_000));
        transactions.executeWithoutResult(transaction -> states.deleteOfDeletedMonitorsWithoutChecks(10_000));
        assertThat(stateExists(deleted)).isFalse();
    }

    @Test
    void deletesNoMoreThanTheBatchSizeInOneTransaction() {
        UUID monitor = newMonitor();
        for (int minute = 1; minute <= 7; minute++) {
            checks.insert(monitor, CUTOFF.minus(Duration.ofMinutes(minute)), HEALTHY);
        }

        Integer firstBatch = transactions.execute(transaction -> checks.deleteOlderThan(CUTOFF, 3));

        assertThat(firstBatch).isEqualTo(3);
        assertThat(job(3).purge().oldChecks()).isEqualTo(4);
        assertThat(checkTimesOf(monitor)).isZero();
    }

    /**
     * Two instances at once: the second takes the rows the first has not locked, without waiting for it, and between
     * them every row is deleted once.
     */
    @Test
    void twoInstancesAtOnceShareTheRowsWithoutWaitingForEachOther() throws Exception {
        UUID monitor = newMonitor();
        for (int minute = 1; minute <= 10; minute++) {
            checks.insert(monitor, CUTOFF.minus(Duration.ofMinutes(minute)), HEALTHY);
        }
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = threads.submit(() -> transactions.execute(transaction -> {
                int deleted = checks.deleteOlderThan(CUTOFF, 4);
                locked.countDown();
                awaitQuietly(release);
                return deleted;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Integer second = transactions.execute(transaction -> checks.deleteOlderThan(CUTOFF, 10));

            assertThat(first).as("the second did not wait for the first").isNotDone();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(4);
            assertThat(second).isEqualTo(6);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        assertThat(checkTimesOf(monitor)).isZero();
    }

    @Test
    void countsTheRowsItDeletesAndTimesEachTable() {
        UUID monitor = newMonitor();
        checks.insert(monitor, CUTOFF.minus(Duration.ofDays(1)), HEALTHY);
        checks.insert(monitor, CUTOFF.minus(Duration.ofDays(2)), HEALTHY);
        double deletedChecks = deletedRows(CheckRetentionJob.CHECKS_TABLE);
        long checkPurges = purges(CheckRetentionJob.CHECKS_TABLE);
        long statePurges = purges(CheckRetentionJob.STATE_TABLE);

        CheckRetentionJob.Purged purged = job(10_000).purge();

        assertThat(deletedRows(CheckRetentionJob.CHECKS_TABLE))
                .isEqualTo(deletedChecks + purged.oldChecks() + purged.checksOfDeletedMonitors());
        assertThat(purges(CheckRetentionJob.CHECKS_TABLE)).isEqualTo(checkPurges + 1);
        assertThat(purges(CheckRetentionJob.STATE_TABLE)).isEqualTo(statePurges + 1);
    }

    private CheckRetentionJob job(int batchSize) {
        return new CheckRetentionJob(
                checks,
                states,
                new CheckRetentionProperties(RETENTION, batchSize),
                transactions,
                meters,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private double deletedRows(String table) {
        return meters.counter(CheckRetentionJob.DELETED_ROWS, "table", table).count();
    }

    private long purges(String table) {
        return meters.timer(CheckRetentionJob.DURATION, "table", table).count();
    }

    private long checkTimesOf(UUID monitor) {
        Long count =
                jdbc.queryForObject("SELECT count(*) FROM monitor_checks WHERE monitor_id = ?", Long.class, monitor);
        return count == null ? 0 : count;
    }

    private boolean checkAt(UUID monitor, Instant checkedAt) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM monitor_checks WHERE monitor_id = ? AND checked_at = ?",
                Long.class,
                monitor,
                checkedAt.atOffset(ZoneOffset.UTC));
        return count != null && count == 1;
    }

    private boolean stateExists(UUID monitor) {
        return states.existsById(monitor);
    }

    /** As {@code MonitorService.delete} leaves it: deleted, and its state paused and unscheduled. */
    private void delete(UUID monitor) {
        jdbc.update("UPDATE monitors SET deleted_at = now() WHERE id = ?", monitor);
        jdbc.update("UPDATE monitor_state SET status = 'PAUSED', next_check_at = NULL WHERE monitor_id = ?", monitor);
    }

    private UUID newMonitor() {
        UUID organization = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID monitor = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                organization);
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, 'Production', now(), now())""", project, organization);
        jdbc.update("""
                INSERT INTO monitors (id, organization_id, project_id, name, url, created_at, updated_at)
                VALUES (?, ?, ?, 'Payments API', 'https://api.example.com/health', now(), now())""", monitor, organization, project);
        jdbc.update("""
                INSERT INTO monitor_state (monitor_id, status_changed_at, updated_at)
                VALUES (?, now(), now())""", monitor);
        return monitor;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
