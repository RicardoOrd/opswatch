package io.github.ricardoord.opswatch.monitoring.domain;

import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * How a monitor checks its URL and when the result counts as a failure (docs/architecture/domain-model.md#monitor).
 * The invariants hold on the whole: building the settings that result from a change checks every field against the
 * others, so a {@code PATCH} that only lowers {@code intervalSeconds} below {@code timeoutMs} is rejected.
 *
 * @param degradedThresholdMs null when a slow but correct answer is never {@code DEGRADED}
 */
public record MonitorSettings(
        ProbeMethod httpMethod,
        int expectedStatusMin,
        int expectedStatusMax,
        int intervalSeconds,
        int timeoutMs,
        @Nullable Integer degradedThresholdMs,
        boolean followRedirects,
        int failureThreshold,
        int recoveryThreshold) {

    public static final int MIN_STATUS = 100;
    public static final int MAX_STATUS = 599;
    public static final int MIN_INTERVAL_SECONDS = 30;
    public static final int MAX_INTERVAL_SECONDS = 3600;
    public static final int MIN_TIMEOUT_MS = 1000;
    public static final int MAX_TIMEOUT_MS = 30_000;
    public static final int MIN_THRESHOLD = 1;
    public static final int MAX_THRESHOLD = 10;

    /** What a new monitor gets for every field the request leaves out. */
    public static final MonitorSettings DEFAULTS =
            new MonitorSettings(ProbeMethod.GET, 200, 299, 60, 10_000, null, true, 3, 2);

    /**
     * @throws InvalidFieldException naming the field of the API that breaks an invariant (400). The request validation
     *     catches the ranges of each field alone; only the rules between fields are left for here
     */
    public MonitorSettings {
        Objects.requireNonNull(httpMethod, "httpMethod");
        requireRange("expectedStatus.min", expectedStatusMin, MIN_STATUS, MAX_STATUS);
        requireRange("expectedStatus.max", expectedStatusMax, MIN_STATUS, MAX_STATUS);
        if (expectedStatusMin > expectedStatusMax) {
            throw new InvalidFieldException("expectedStatus", "min-above-max", "min must not be greater than max");
        }
        requireRange("intervalSeconds", intervalSeconds, MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS);
        requireRange("timeoutMs", timeoutMs, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
        if (timeoutMs >= intervalSeconds * 1000L) {
            throw new InvalidFieldException(
                    "timeoutMs", "not-below-interval", "must be less than intervalSeconds, in milliseconds");
        }
        if (degradedThresholdMs != null) {
            requireRange("degradedThresholdMs", degradedThresholdMs, 1, MAX_TIMEOUT_MS);
            if (degradedThresholdMs > timeoutMs) {
                throw new InvalidFieldException(
                        "degradedThresholdMs", "above-timeout", "must not be greater than timeoutMs");
            }
        }
        requireRange("failureThreshold", failureThreshold, MIN_THRESHOLD, MAX_THRESHOLD);
        requireRange("recoveryThreshold", recoveryThreshold, MIN_THRESHOLD, MAX_THRESHOLD);
    }

    private static void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new InvalidFieldException(field, "range", "must be between " + min + " and " + max);
        }
    }
}
