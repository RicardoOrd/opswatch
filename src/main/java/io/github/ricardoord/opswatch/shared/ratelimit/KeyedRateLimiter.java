package io.github.ricardoord.opswatch.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.ricardoord.opswatch.shared.error.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One rate limit applied per key: a bucket for each, in a cache bounded in size and in time
 * (docs/security/security-architecture.md#8-rate-limiting). In memory: exact with one instance, multiplied by the
 * number of instances with more (ADR-009).
 *
 * <p>Nothing gets locked: a request over the limit is rejected until the bucket refills, so whoever floods a key cannot
 * keep it closed any longer than the flood itself.
 */
public final class KeyedRateLimiter {

    /**
     * Keys a limiter remembers at most. An entry takes a few hundred bytes, so a limiter stays within a few MB however
     * many keys a client sends. A full cache keeps the keys used most, which are the ones under attack.
     */
    public static final int MAX_KEYS = 10_000;

    private final Bandwidth bandwidth;
    private final TimeMeter time;
    private final Cache<String, KeyBucket> buckets;

    public KeyedRateLimiter(RateLimit limit, Clock clock) {
        this.bandwidth = Bandwidth.builder()
                .capacity(limit.capacity())
                .refillGreedy(limit.capacity(), limit.period())
                .build();
        this.time = new ClockTimeMeter(clock);
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_KEYS)
                // An idle bucket is full again after one period: forgetting it then changes nothing
                .expireAfterAccess(limit.period())
                .ticker(time::currentTimeNanos)
                .build();
    }

    /**
     * Takes one request from the bucket of the key.
     *
     * @param onLimitReached run once when the key hits the limit, not once per rejected request: a flood of requests
     *     must not become a flood of log lines. It runs again after a request of the key is allowed
     * @throws RateLimitExceededException with the time until the next request is allowed
     */
    public void consume(String key, Runnable onLimitReached) {
        KeyBucket bucket = buckets.get(key, unused -> new KeyBucket(bandwidth, time));
        ConsumptionProbe probe = bucket.tokens.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            bucket.rejecting.set(false);
            return;
        }
        if (bucket.rejecting.compareAndSet(false, true)) {
            onLimitReached.run();
        }
        throw new RateLimitExceededException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private static final class KeyBucket {

        private final Bucket tokens;

        /** Whether the last request was rejected, so that the limit is reported once per burst. */
        private final AtomicBoolean rejecting = new AtomicBoolean();

        KeyBucket(Bandwidth bandwidth, TimeMeter time) {
            this.tokens = Bucket.builder()
                    .addLimit(bandwidth)
                    .withCustomTimePrecision(time)
                    .build();
        }
    }

    /** Bucket4j and Caffeine read the injected clock, so tests can move time forward. */
    private record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            Instant now = clock.instant();
            return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
