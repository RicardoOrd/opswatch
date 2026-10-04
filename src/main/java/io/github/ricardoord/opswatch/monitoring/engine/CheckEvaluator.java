package io.github.ricardoord.opswatch.monitoring.engine;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.engine.HttpObservation.Failure;
import io.github.ricardoord.opswatch.monitoring.engine.HttpObservation.Response;
import java.time.Duration;

/**
 * Judges what a check observed against the settings of its monitor
 * (docs/architecture/monitoring-engine.md#5-httpmonitorclient). A pure function: the client observes, this decides. It
 * lives next to the observation and not in {@code domain}, which does not depend on the engine.
 */
public final class CheckEvaluator {

    /** The detail of a response that came, but after the timeout. */
    static final String LATE_RESPONSE = "response after the timeout";

    private CheckEvaluator() {}

    /**
     * In this order: no response is its failure; a response later than {@code timeoutMs} (it arrived within the margin
     * of the deadline) is {@code TIMEOUT}; a code outside the expected range is {@code UNEXPECTED_STATUS}; a correct
     * answer slower than {@code degradedThresholdMs} is {@code DEGRADED}; the rest is {@code UP}.
     */
    public static CheckOutcome evaluate(MonitorSettings settings, HttpObservation observation) {
        return switch (observation) {
            case Failure failure -> CheckOutcome.down(failure.reason(), null, null, failure.detail());
            case Response response
            when isLate(settings, response.responseTime()) ->
                CheckOutcome.down(FailureReason.TIMEOUT, response.statusCode(), response.responseTime(), LATE_RESPONSE);
            case Response response
            when !isExpected(settings, response.statusCode()) ->
                CheckOutcome.down(
                        FailureReason.UNEXPECTED_STATUS, response.statusCode(), response.responseTime(), null);
            case Response response
            when isDegraded(settings, response.responseTime()) ->
                CheckOutcome.degraded(response.statusCode(), response.responseTime());
            case Response response -> CheckOutcome.up(response.statusCode(), response.responseTime());
        };
    }

    private static boolean isLate(MonitorSettings settings, Duration responseTime) {
        return responseTime.compareTo(Duration.ofMillis(settings.timeoutMs())) > 0;
    }

    private static boolean isExpected(MonitorSettings settings, int statusCode) {
        return statusCode >= settings.expectedStatusMin() && statusCode <= settings.expectedStatusMax();
    }

    private static boolean isDegraded(MonitorSettings settings, Duration responseTime) {
        Integer threshold = settings.degradedThresholdMs();
        return threshold != null && responseTime.compareTo(Duration.ofMillis(threshold)) > 0;
    }
}
