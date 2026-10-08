package io.github.ricardoord.opswatch.notification.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How deliveries are sent (docs/devops/environments.md#notificaciones).
 *
 * @param enabled whether this instance sends; false for one that only serves the API, and in the {@code test} profile.
 *     {@code DeliveryWorker} and {@code EmailSender} read it as a condition
 * @param pollInterval between two claims; {@code DeliveryWorker} reads it from its {@code @Scheduled}
 * @param batchSize deliveries claimed at most in one poll, all sent at once
 * @param maxAttempts after the last one fails, the delivery is {@code FAILED}
 * @param backoff the wait before each attempt, the first one included: as many as {@code maxAttempts}
 * @param lease how long a claimed delivery is set aside while it is sent. Longer than any send can take, its SMTP
 *     timeouts included: when it runs out, another worker may send it again
 */
@ConfigurationProperties("opswatch.notification.delivery")
public record DeliveryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("6") int maxAttempts,

        @DefaultValue({"0s", "30s", "2m", "10m", "30m", "1h"})
        List<Duration> backoff,

        @DefaultValue("5m") Duration lease) {

    public DeliveryProperties {
        backoff = List.copyOf(backoff);
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("opswatch.notification.delivery.poll-interval must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("opswatch.notification.delivery.batch-size must be at least 1");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("opswatch.notification.delivery.max-attempts must be at least 1");
        }
        if (backoff.size() != maxAttempts) {
            throw new IllegalArgumentException("opswatch.notification.delivery.backoff must have one wait per attempt: "
                    + maxAttempts + ", not " + backoff.size());
        }
        if (backoff.stream().anyMatch(Duration::isNegative)) {
            throw new IllegalArgumentException("opswatch.notification.delivery.backoff must not be negative");
        }
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("opswatch.notification.delivery.lease must be positive");
        }
    }

    /** The wait before the first attempt, from when the delivery is created. */
    public Duration firstWait() {
        return backoff.getFirst();
    }

    /**
     * @param failedAttempt the attempt that just failed, from 1; not the last
     * @return the wait before the next one
     */
    public Duration waitAfter(int failedAttempt) {
        if (failedAttempt < 1 || failedAttempt >= maxAttempts) {
            throw new IllegalArgumentException("No attempt follows attempt " + failedAttempt);
        }
        return backoff.get(failedAttempt);
    }

    /** @param attempt from 1 */
    public boolean isLast(int attempt) {
        return attempt >= maxAttempts;
    }
}
