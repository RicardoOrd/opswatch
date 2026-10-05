package io.github.ricardoord.opswatch.monitoring.web;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.application.MonitorStatsQueries.WindowStats;
import io.github.ricardoord.opswatch.monitoring.domain.CheckStats;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The checks of a monitor in a window that ends now (docs/api/endpoints-v1.md#checks-y-estadísticas).
 *
 * @param window {@code 24h}, {@code 7d} or {@code 30d}
 * @param from inclusive
 * @param to exclusive
 * @param uptimePercent {@code (up + degraded) / totalChecks × 100}, with 3 decimals; null with no checks
 * @param responseTimeMs over the checks that got a response, in whole milliseconds; each null when none did
 * @param failuresByReason the {@code DOWN} checks by why, only the reasons that happened
 */
public record MonitorStatsResponse(
        String window,
        Instant from,
        Instant to,
        long totalChecks,
        long up,
        long degraded,
        long down,
        @Nullable BigDecimal uptimePercent,
        CheckStats.ResponseTimes responseTimeMs,
        Map<FailureReason, Long> failuresByReason) {

    static MonitorStatsResponse from(WindowStats window) {
        CheckStats stats = window.stats();
        return new MonitorStatsResponse(
                window.window().label(),
                window.from(),
                window.to(),
                stats.total(),
                stats.up(),
                stats.degraded(),
                stats.down(),
                stats.uptimePercent(),
                stats.responseTimeMs(),
                stats.failuresByReason());
    }
}
