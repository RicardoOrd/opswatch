package io.github.ricardoord.opswatch.notification.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The delivery part of {@code opswatch.retention} (docs/devops/environments.md#retención). The schedule is
 * {@code opswatch.retention.cron}, read by {@link DeliveryRetentionJob}.
 *
 * @param deliveries how long a delivery is kept, whatever its status
 * @param batchSize rows deleted per transaction
 */
@ConfigurationProperties("opswatch.retention")
public record DeliveryRetentionProperties(
        @DefaultValue("90d") Duration deliveries,
        @DefaultValue("10000") int batchSize) {

    public DeliveryRetentionProperties {
        if (deliveries.isNegative() || deliveries.isZero()) {
            throw new IllegalArgumentException("opswatch.retention.deliveries must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("opswatch.retention.batch-size must be positive");
        }
    }
}
