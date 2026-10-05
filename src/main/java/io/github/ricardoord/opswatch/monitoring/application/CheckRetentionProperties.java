package io.github.ricardoord.opswatch.monitoring.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The check part of {@code opswatch.retention} (docs/devops/environments.md#retención). The schedule is
 * {@code opswatch.retention.cron}, read by {@link CheckRetentionJob}.
 *
 * @param checks how long a check is kept; the longest window of the statistics is as long
 * @param batchSize rows deleted per transaction
 */
@ConfigurationProperties("opswatch.retention")
public record CheckRetentionProperties(
        @DefaultValue("30d") Duration checks,
        @DefaultValue("10000") int batchSize) {

    public CheckRetentionProperties {
        if (checks.isNegative() || checks.isZero()) {
            throw new IllegalArgumentException("opswatch.retention.checks must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("opswatch.retention.batch-size must be positive");
        }
    }
}
