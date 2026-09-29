package io.github.ricardoord.opswatch.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateLimitExceededExceptionTest {

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 1", "999, 1", "1000, 1", "1001, 2", "6000, 6", "720000, 720"})
    void retryAfterIsInWholeSecondsRoundedUp(long millis, long seconds) {
        var exception = new RateLimitExceededException(Duration.ofMillis(millis));

        assertThat(exception.retryAfterSeconds()).isEqualTo(seconds);
    }
}
