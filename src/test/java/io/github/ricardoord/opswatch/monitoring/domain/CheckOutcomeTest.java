package io.github.ricardoord.opswatch.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The constraints of {@code monitor_checks}, before the database has to refuse anything. */
class CheckOutcomeTest {

    @Test
    void aDownCheckAndOnlyADownCheckHasAReason() {
        assertThatThrownBy(() -> new CheckOutcome(CheckStatus.DOWN, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new CheckOutcome(CheckStatus.UP, 200, Duration.ofMillis(5), FailureReason.TIMEOUT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDetailFitsItsColumn() {
        String longest = "x".repeat(CheckOutcome.MAX_DETAIL_LENGTH);

        assertThat(CheckOutcome.down(FailureReason.TIMEOUT, null, null, longest).errorDetail())
                .isEqualTo(longest);
        assertThatThrownBy(() -> CheckOutcome.down(FailureReason.TIMEOUT, null, null, longest + "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storesTheTimeInMilliseconds() {
        assertThat(CheckOutcome.up(200, Duration.ofNanos(143_999_999)).responseTimeMs())
                .isEqualTo(143);
        assertThat(CheckOutcome.down(FailureReason.DNS_FAILURE, null, null, null)
                        .responseTimeMs())
                .isNull();
    }
}
