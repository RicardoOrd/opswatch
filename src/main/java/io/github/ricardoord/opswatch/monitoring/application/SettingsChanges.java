package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.domain.ProbeMethod;
import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import org.jspecify.annotations.Nullable;

/**
 * The settings a request sends; a null field keeps its value. Applied to {@link MonitorSettings#DEFAULTS} on creation
 * and to the current settings on a {@code PATCH}, and the result is checked as a whole.
 *
 * @param degradedThresholdMs absent to keep it, null to turn the degraded state off
 */
public record SettingsChanges(
        @Nullable ProbeMethod httpMethod,
        @Nullable Integer expectedStatusMin,
        @Nullable Integer expectedStatusMax,
        @Nullable Integer intervalSeconds,
        @Nullable Integer timeoutMs,
        PatchField<Integer> degradedThresholdMs,
        @Nullable Boolean followRedirects,
        @Nullable Integer failureThreshold,
        @Nullable Integer recoveryThreshold) {

    /** @throws InvalidFieldException if the result breaks an invariant of {@link MonitorSettings} (400) */
    MonitorSettings applyTo(MonitorSettings current) {
        return new MonitorSettings(
                orElse(httpMethod, current.httpMethod()),
                orElse(expectedStatusMin, current.expectedStatusMin()),
                orElse(expectedStatusMax, current.expectedStatusMax()),
                orElse(intervalSeconds, current.intervalSeconds()),
                orElse(timeoutMs, current.timeoutMs()),
                degradedThresholdMs.orElse(current.degradedThresholdMs()),
                orElse(followRedirects, current.followRedirects()),
                orElse(failureThreshold, current.failureThreshold()),
                orElse(recoveryThreshold, current.recoveryThreshold()));
    }

    private static <T> T orElse(@Nullable T sent, T current) {
        return sent != null ? sent : current;
    }
}
