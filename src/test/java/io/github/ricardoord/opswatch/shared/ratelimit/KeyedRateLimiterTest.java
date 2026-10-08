package io.github.ricardoord.opswatch.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.shared.error.RateLimitExceededException;
import io.github.ricardoord.opswatch.shared.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The limits of authentication ({@code AuthRateLimiterTest}) and of the test of a channel are built on this. */
class KeyedRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T10:00:00Z"));
    private final KeyedRateLimiter limiter = new KeyedRateLimiter(RateLimit.valueOf("5/1m"), clock);
    private final AtomicInteger reports = new AtomicInteger();

    @Test
    void rejectsTheRequestOverTheLimitWithTheWaitUntilATokenRefills() {
        for (int i = 0; i < 5; i++) {
            consume("channel-1");
        }

        // A token every 12 s: 5 per minute
        assertThatThrownBy(() -> consume("channel-1"))
                .isInstanceOfSatisfying(
                        RateLimitExceededException.class,
                        ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(12));
        clock.advance(Duration.ofSeconds(12));
        assertThatCode(() -> consume("channel-1")).doesNotThrowAnyException();
    }

    @Test
    void eachKeyHasABucketOfItsOwn() {
        for (int i = 0; i < 5; i++) {
            consume("channel-1");
        }

        assertThatCode(() -> consume("channel-2")).doesNotThrowAnyException();
    }

    @Test
    void reportsTheLimitOncePerBurst() {
        for (int i = 0; i < 5; i++) {
            consume("channel-1");
        }
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> consume("channel-1")).isInstanceOf(RateLimitExceededException.class);
        }
        assertThat(reports).hasValue(1);

        clock.advance(Duration.ofSeconds(12));
        consume("channel-1");
        assertThatThrownBy(() -> consume("channel-1")).isInstanceOf(RateLimitExceededException.class);

        assertThat(reports).hasValue(2);
    }

    private void consume(String key) {
        limiter.consume(key, reports::incrementAndGet);
    }
}
