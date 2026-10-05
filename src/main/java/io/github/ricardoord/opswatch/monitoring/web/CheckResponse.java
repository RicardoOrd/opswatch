package io.github.ricardoord.opswatch.monitoring.web;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.CheckStatus;
import io.github.ricardoord.opswatch.monitoring.domain.RecordedCheck;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One check of a monitor.
 *
 * @param checkedAt when it started
 * @param httpStatus null if there was no response
 * @param responseTimeMs up to the headers of the final response; null if there was no response
 * @param failureReason set exactly when it is {@code DOWN}
 * @param errorDetail a generic text of OpsWatch, never anything the target sent
 */
public record CheckResponse(
        Instant checkedAt,
        CheckStatus status,
        @Nullable Integer httpStatus,
        @Nullable Integer responseTimeMs,
        @Nullable FailureReason failureReason,
        @Nullable String errorDetail) {

    static CheckResponse from(RecordedCheck check) {
        CheckOutcome outcome = check.outcome();
        return new CheckResponse(
                check.checkedAt(),
                outcome.status(),
                outcome.httpStatus(),
                outcome.responseTimeMs(),
                outcome.failureReason(),
                outcome.errorDetail());
    }
}
