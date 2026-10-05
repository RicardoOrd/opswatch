package io.github.ricardoord.opswatch.monitoring.domain;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What one check concluded, as {@code monitor_checks} keeps it.
 *
 * @param httpStatus null if there was no response
 * @param responseTime up to the headers of the final response; null if there was no response
 * @param failureReason set exactly when the check is {@code DOWN}
 * @param errorDetail a generic text of OpsWatch, never anything the target sent
 */
public record CheckOutcome(
        CheckStatus status,
        @Nullable Integer httpStatus,
        @Nullable Duration responseTime,
        @Nullable FailureReason failureReason,
        @Nullable String errorDetail) {

    public static final int MAX_DETAIL_LENGTH = 255;

    public CheckOutcome {
        Objects.requireNonNull(status, "status");
        if ((status == CheckStatus.DOWN) != (failureReason != null)) {
            throw new IllegalArgumentException("A DOWN check, and only a DOWN check, has a failure reason");
        }
        if (errorDetail != null && errorDetail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("errorDetail must have at most " + MAX_DETAIL_LENGTH + " characters");
        }
    }

    public static CheckOutcome up(int httpStatus, Duration responseTime) {
        return new CheckOutcome(CheckStatus.UP, httpStatus, responseTime, null, null);
    }

    public static CheckOutcome degraded(int httpStatus, Duration responseTime) {
        return new CheckOutcome(CheckStatus.DEGRADED, httpStatus, responseTime, null, null);
    }

    public static CheckOutcome down(
            FailureReason reason,
            @Nullable Integer httpStatus,
            @Nullable Duration responseTime,
            @Nullable String detail) {
        return new CheckOutcome(CheckStatus.DOWN, httpStatus, responseTime, reason, detail);
    }

    /** As the column stores it. */
    public @Nullable Integer responseTimeMs() {
        return responseTime == null ? null : Math.toIntExact(responseTime.toMillis());
    }
}
