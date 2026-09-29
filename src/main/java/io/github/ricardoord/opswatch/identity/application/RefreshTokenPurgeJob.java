package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.identity.domain.RefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the refresh tokens that stopped being useful more than the grace period ago (docs/database/data-retention.md).
 * In batches, each in its own short transaction, so it never holds locks for long.
 *
 * <p>No lock between instances: deleting is idempotent, so two instances running it at once only share the work.
 */
@Component
@EnableConfigurationProperties(RefreshTokenRetentionProperties.class)
public class RefreshTokenPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenPurgeJob.class);

    private final RefreshTokenRepository refreshTokens;
    private final RefreshTokenRetentionProperties properties;
    private final Clock clock;

    public RefreshTokenPurgeJob(
            RefreshTokenRepository refreshTokens, RefreshTokenRetentionProperties properties, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${opswatch.retention.cron:0 30 3 * * *}", zone = "UTC")
    void scheduled() {
        purge();
    }

    /** @return how many tokens it deleted */
    public int purge() {
        Instant cutoff = clock.instant().minus(properties.refreshTokensGrace());
        int total = 0;
        int deleted;
        do {
            deleted = refreshTokens.deleteSpentBefore(cutoff, properties.batchSize());
            total += deleted;
        } while (deleted == properties.batchSize());
        log.info("Purged {} refresh tokens spent before {}", total, cutoff);
        return total;
    }
}
