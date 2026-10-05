package io.github.ricardoord.opswatch.monitoring.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.domain.ProbeMethod;
import io.github.ricardoord.opswatch.monitoring.engine.HttpObservation.Failure;
import io.github.ricardoord.opswatch.monitoring.engine.HttpObservation.Response;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class CheckEvaluatorTest {

    /** Expects 200 to 299, a timeout of 5 s and a degradation threshold of 800 ms. */
    private static final MonitorSettings SETTINGS =
            new MonitorSettings(ProbeMethod.GET, 200, 299, 60, 5_000, 800, true, 3, 2);

    @ParameterizedTest(name = "{0} in {1} ms -> {2}")
    @CsvSource({
        "200,  120, UP",
        "299,  800, UP",
        "204,  801, DEGRADED",
        "200, 5000, DEGRADED",
        "199,  120, DOWN",
        "300,  120, DOWN",
        "503,  120, DOWN"
    })
    void judgesTheCodeAndTheTimeOfAResponse(int statusCode, long millis, String expected) {
        CheckOutcome outcome = CheckEvaluator.evaluate(SETTINGS, response(statusCode, millis));

        assertThat(outcome.status().name()).isEqualTo(expected);
        assertThat(outcome.httpStatus()).isEqualTo(statusCode);
        assertThat(outcome.responseTimeMs()).isEqualTo((int) millis);
    }

    @Test
    void aCodeOutsideTheRangeIsAnUnexpectedStatusWithoutDetail() {
        CheckOutcome outcome = CheckEvaluator.evaluate(SETTINGS, response(500, 3000));

        assertThat(outcome)
                .isEqualTo(CheckOutcome.down(FailureReason.UNEXPECTED_STATUS, 500, Duration.ofMillis(3000), null));
    }

    /** The deadline gives a margin over the timeout to cut the request; an answer inside that margin is still late. */
    @Test
    void aResponseAfterTheTimeoutIsATimeoutEvenWithAnExpectedCode() {
        CheckOutcome outcome = CheckEvaluator.evaluate(SETTINGS, response(200, 5001));

        assertThat(outcome)
                .isEqualTo(CheckOutcome.down(
                        FailureReason.TIMEOUT, 200, Duration.ofMillis(5001), CheckEvaluator.LATE_RESPONSE));
    }

    @Test
    void withoutADegradationThresholdASlowAnswerIsUp() {
        MonitorSettings noThreshold = new MonitorSettings(ProbeMethod.GET, 200, 299, 60, 5_000, null, true, 3, 2);

        assertThat(CheckEvaluator.evaluate(noThreshold, response(200, 4999))
                        .status()
                        .name())
                .isEqualTo("UP");
    }

    /** The user may expect a redirect, when the monitor does not follow them. */
    @Test
    void theRangeMayExpectARedirect() {
        MonitorSettings redirect = new MonitorSettings(ProbeMethod.HEAD, 301, 301, 60, 5_000, null, false, 3, 2);

        assertThat(CheckEvaluator.evaluate(redirect, response(301, 50)).status().name())
                .isEqualTo("UP");
    }

    @ParameterizedTest
    @EnumSource(
            value = FailureReason.class,
            names = {"UNEXPECTED_STATUS"},
            mode = EnumSource.Mode.EXCLUDE)
    void aFailureIsDownForItsReasonWithItsDetailAndNoResponse(FailureReason reason) {
        CheckOutcome outcome =
                CheckEvaluator.evaluate(SETTINGS, new Failure(reason, Duration.ofMillis(5200), "a detail"));

        assertThat(outcome).isEqualTo(CheckOutcome.down(reason, null, null, "a detail"));
    }

    private static Response response(int statusCode, long millis) {
        return new Response(statusCode, Duration.ofMillis(millis), 0);
    }
}
