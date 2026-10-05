package io.github.ricardoord.opswatch.monitoring.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A row of {@code monitor_checks}: what one check concluded, and when it started.
 *
 * @param checkedAt unique among the checks of its monitor: the position of the cursor of its history
 */
public record RecordedCheck(Instant checkedAt, CheckOutcome outcome) {

    public RecordedCheck {
        Objects.requireNonNull(checkedAt, "checkedAt");
        Objects.requireNonNull(outcome, "outcome");
    }
}
