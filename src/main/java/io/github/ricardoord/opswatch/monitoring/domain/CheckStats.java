package io.github.ricardoord.opswatch.monitoring.domain;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The checks of a monitor in a window of time, summed up (docs/api/endpoints-v1.md#checks-y-estadísticas). Only the
 * checks that were made count: a paused monitor makes none, so the time it spends paused neither adds to its uptime
 * nor takes from it.
 *
 * @param responseTimeMs over the checks that got a response, whatever their status; empty when none did
 * @param failuresByReason the {@code DOWN} checks, by why; only the reasons that happened
 */
public record CheckStats(
        long total,
        long up,
        long degraded,
        long down,
        ResponseTimes responseTimeMs,
        Map<FailureReason, Long> failuresByReason) {

    public CheckStats {
        Objects.requireNonNull(responseTimeMs, "responseTimeMs");
        failuresByReason =
                failuresByReason.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(failuresByReason));
    }

    /**
     * {@code (up + degraded) / total × 100}, with 3 decimals: a {@code DEGRADED} check answered correctly, only slowly.
     *
     * @return null with no checks, rather than a made-up 100 or 0
     */
    public @Nullable BigDecimal uptimePercent() {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf((up + degraded) * 100).divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP);
    }

    /** In whole milliseconds, rounded; each null when no check got a response. */
    public record ResponseTimes(
            @Nullable Integer avg,
            @Nullable Integer p50,
            @Nullable Integer p95,
            @Nullable Integer p99) {}
}
