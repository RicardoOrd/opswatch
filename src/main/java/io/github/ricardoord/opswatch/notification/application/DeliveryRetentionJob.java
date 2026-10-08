package io.github.ricardoord.opswatch.notification.application;

import io.github.ricardoord.opswatch.notification.domain.DeliveryQueue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Keeps {@code notification_deliveries} within its retention (docs/database/data-retention.md), daily: the deliveries
 * created more than {@code opswatch.retention.deliveries} ago, whatever their status.
 *
 * <p>In batches of {@code opswatch.retention.batch-size}, each in a short transaction of its own, as the purge of the
 * checks: no lock between instances, every batch takes its rows with {@code SKIP LOCKED}.
 */
@Component
@EnableConfigurationProperties(DeliveryRetentionProperties.class)
public class DeliveryRetentionJob {

    /** {@code opswatch_retention_deleted_rows_total{table}} in Prometheus, shared with the other purges. */
    static final String DELETED_ROWS = "opswatch.retention.deleted.rows";

    /** {@code opswatch_retention_duration_seconds{table}} in Prometheus, shared with the other purges. */
    static final String DURATION = "opswatch.retention.duration";

    static final String TABLE = "notification_deliveries";

    private static final Logger log = LoggerFactory.getLogger(DeliveryRetentionJob.class);

    private final DeliveryQueue queue;
    private final DeliveryRetentionProperties properties;
    private final TransactionOperations transactions;
    private final MeterRegistry meters;
    private final Clock clock;

    DeliveryRetentionJob(
            DeliveryQueue queue,
            DeliveryRetentionProperties properties,
            TransactionOperations transactions,
            MeterRegistry meters,
            Clock clock) {
        this.queue = queue;
        this.properties = properties;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(cron = "${opswatch.retention.cron:0 30 3 * * *}", zone = "UTC")
    void scheduled() {
        purge();
    }

    /** @return how many deliveries it deleted */
    public long purge() {
        Instant cutoff = clock.instant().minus(properties.deliveries());
        int batch = properties.batchSize();
        Timer.Sample time = Timer.start(meters);
        long total = 0;
        int deleted;
        do {
            deleted = Objects.requireNonNull(
                    transactions.execute(transaction -> queue.deleteCreatedBefore(cutoff, batch)));
            total += deleted;
            meters.counter(DELETED_ROWS, "table", TABLE).increment(deleted);
        } while (deleted == batch);
        time.stop(meters.timer(DURATION, "table", TABLE));
        log.info("Purged {} notification deliveries created before {}", total, cutoff);
        return total;
    }
}
