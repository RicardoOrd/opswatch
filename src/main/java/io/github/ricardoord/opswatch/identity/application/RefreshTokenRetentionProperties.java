package io.github.ricardoord.opswatch.identity.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The refresh token part of {@code opswatch.retention} (docs/devops/environments.md#retención). The schedule is
 * {@code opswatch.retention.cron}, read by {@link RefreshTokenPurgeJob}.
 *
 * @param refreshTokensGrace how long a token is kept after it expires or is revoked
 * @param batchSize rows deleted per transaction
 */
@ConfigurationProperties("opswatch.retention")
public record RefreshTokenRetentionProperties(
        @DefaultValue("7d") Duration refreshTokensGrace,
        @DefaultValue("10000") int batchSize) {

    public RefreshTokenRetentionProperties {
        if (refreshTokensGrace.isNegative() || batchSize < 1) {
            throw new IllegalArgumentException(
                    "opswatch.retention.refresh-tokens-grace cannot be negative and batch-size must be positive");
        }
    }
}
