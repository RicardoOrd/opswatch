package io.github.ricardoord.opswatch.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.notification.NotificationRows;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import io.github.ricardoord.opswatch.notification.domain.DeliveryQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The purge against PostgreSQL. Its deliveries are from 1995, a past no other test uses: the cutoff falls among them
 * and after nothing else.
 */
@IntegrationTest
class DeliveryRetentionJobIT {

    private static final Instant NOW = Instant.parse("1995-06-01T00:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(90);
    private static final Instant CUTOFF = NOW.minus(RETENTION);

    @Autowired
    private DeliveryQueue queue;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** Whatever their status, a pending one included: none is retried for anything near 90 days. */
    @Test
    void deletesTheDeliveriesCreatedBeforeTheRetentionInBatches() {
        UUID channel = newChannel();
        UUID oldSent = test(channel, CUTOFF.minusSeconds(1));
        UUID oldPending = test(channel, CUTOFF.minus(Duration.ofDays(1)));
        UUID oldFailed = test(channel, CUTOFF.minus(Duration.ofDays(2)));
        jdbc.update("UPDATE notification_deliveries SET status = 'SENT' WHERE id = ?", oldSent);
        jdbc.update("UPDATE notification_deliveries SET status = 'FAILED' WHERE id = ?", oldFailed);
        UUID atTheCutoff = test(channel, CUTOFF);
        UUID recent = test(channel, NOW.minus(Duration.ofDays(1)));

        // Batches of 2: three deleted, so a full batch and a short one
        long purged = job(2).purge();

        assertThat(purged).isEqualTo(3);
        assertThat(exists(oldSent)).isFalse();
        assertThat(exists(oldPending)).isFalse();
        assertThat(exists(oldFailed)).isFalse();
        assertThat(exists(atTheCutoff)).isTrue();
        assertThat(exists(recent)).isTrue();
        assertThat(meters.counter(DeliveryRetentionJob.DELETED_ROWS, "table", DeliveryRetentionJob.TABLE)
                        .count())
                .isEqualTo(3);
        assertThat(meters.timer(DeliveryRetentionJob.DURATION, "table", DeliveryRetentionJob.TABLE)
                        .count())
                .isOne();
    }

    private DeliveryRetentionJob job(int batchSize) {
        return new DeliveryRetentionJob(
                queue,
                new DeliveryRetentionProperties(RETENTION, batchSize),
                transactions,
                meters,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UUID test(UUID channel, Instant createdAt) {
        UUID id = UUID.randomUUID();
        queue.addTest(id, channel, createdAt, createdAt);
        return id;
    }

    private boolean exists(UUID delivery) {
        Long found =
                jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE id = ?", Long.class, delivery);
        return found != null && found > 0;
    }

    private UUID newChannel() {
        NotificationRows rows = new NotificationRows(jdbc);
        return rows.channel(rows.organization(), null, ChannelType.EMAIL, true);
    }
}
