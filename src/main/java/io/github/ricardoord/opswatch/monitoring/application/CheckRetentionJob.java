package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.monitoring.domain.MonitorCheckRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Keeps {@code monitor_checks} within its retention (docs/database/data-retention.md#job-de-purga-de-checks), daily:
 *
 * <ol>
 *   <li>the checks older than {@code opswatch.retention.checks};
 *   <li>the checks of deleted monitors, whatever their age;
 *   <li>then the state of those monitors, once none of their checks is left. Their row of {@code monitors} stays.
 * </ol>
 *
 * <p>In batches of {@code opswatch.retention.batch-size}, each in a short transaction of its own, so it never holds
 * locks for long and autovacuum can reclaim the space as it goes. No lock between instances, as in the other
 * purges: every batch takes its rows with {@code SKIP LOCKED}, so two instances share the
 * work without repeating it or waiting for each other, and none holds a connection for the whole purge.
 */
@Component
@EnableConfigurationProperties(CheckRetentionProperties.class)
public class CheckRetentionJob {

    /** {@code opswatch_retention_deleted_rows_total{table}} in Prometheus. */
    static final String DELETED_ROWS = "opswatch.retention.deleted.rows";

    /** {@code opswatch_retention_duration_seconds{table}} in Prometheus. */
    static final String DURATION = "opswatch.retention.duration";

    static final String CHECKS_TABLE = "monitor_checks";
    static final String STATE_TABLE = "monitor_state";

    private static final Logger log = LoggerFactory.getLogger(CheckRetentionJob.class);

    private final MonitorCheckRepository checks;
    private final MonitorStateRepository states;
    private final CheckRetentionProperties properties;
    private final TransactionOperations transactions;
    private final MeterRegistry meters;
    private final Clock clock;

    CheckRetentionJob(
            MonitorCheckRepository checks,
            MonitorStateRepository states,
            CheckRetentionProperties properties,
            TransactionOperations transactions,
            MeterRegistry meters,
            Clock clock) {
        this.checks = checks;
        this.states = states;
        this.properties = properties;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(cron = "${opswatch.retention.cron:0 30 3 * * *}", zone = "UTC")
    void scheduled() {
        purge();
    }

    public Purged purge() {
        Instant cutoff = clock.instant().minus(properties.checks());
        int batch = properties.batchSize();
        Timer.Sample checksTime = Timer.start(meters);
        long old = inBatches(CHECKS_TABLE, () -> checks.deleteOlderThan(cutoff, batch));
        long ofDeleted = inBatches(CHECKS_TABLE, () -> checks.deleteOfDeletedMonitors(batch));
        checksTime.stop(meters.timer(DURATION, "table", CHECKS_TABLE));
        Timer.Sample statesTime = Timer.start(meters);
        long statesOfDeleted = inBatches(STATE_TABLE, () -> states.deleteOfDeletedMonitorsWithoutChecks(batch));
        statesTime.stop(meters.timer(DURATION, "table", STATE_TABLE));
        Purged purged = new Purged(old, ofDeleted, statesOfDeleted);
        log.info(
                "Purged {} checks older than {}, {} checks of deleted monitors and {} states of deleted monitors",
                old,
                cutoff,
                ofDeleted,
                statesOfDeleted);
        return purged;
    }

    /** Until a batch comes back short: then nothing was left that another instance was not already deleting. */
    private long inBatches(String table, IntSupplier deleteBatch) {
        long total = 0;
        int deleted;
        do {
            deleted = Objects.requireNonNull(transactions.execute(transaction -> deleteBatch.getAsInt()));
            total += deleted;
            meters.counter(DELETED_ROWS, "table", table).increment(deleted);
        } while (deleted == properties.batchSize());
        return total;
    }

    /**
     * @param oldChecks older than the retention
     * @param checksOfDeletedMonitors whatever their age
     * @param statesOfDeletedMonitors once their checks were gone
     */
    public record Purged(long oldChecks, long checksOfDeletedMonitors, long statesOfDeletedMonitors) {}
}
