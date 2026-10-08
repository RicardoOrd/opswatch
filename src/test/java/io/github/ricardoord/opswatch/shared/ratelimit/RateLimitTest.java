package io.github.ricardoord.opswatch.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RateLimitTest {

    @Test
    void parsesCapacityAndPeriod() {
        assertThat(RateLimit.valueOf("10/1m")).isEqualTo(new RateLimit(10, Duration.ofMinutes(1)));
        assertThat(RateLimit.valueOf("5/15m")).isEqualTo(new RateLimit(5, Duration.ofMinutes(15)));
        assertThat(RateLimit.valueOf(" 30 / 1h ")).isEqualTo(new RateLimit(30, Duration.ofHours(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10/", "/1m", "ten/1m", "10/a minute", "0/1m", "-1/1m", "10/0s", "10/-1m"})
    void rejectsAnythingElse(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RateLimit.valueOf(value))
                .withMessageContaining("like 10/1m")
                .withMessageContaining(value);
    }
}
