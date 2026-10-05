package io.github.ricardoord.opswatch.monitoring.engine;

import io.github.ricardoord.opswatch.monitoring.domain.MonitorSnapshot;
import java.time.Instant;
import java.util.Objects;

/**
 * A check this instance has claimed and must run, with everything read when it was claimed: the request goes out with
 * no transaction open.
 *
 * @param request with the headers already decrypted, which never appear in its {@code toString()}
 * @param scheduledFor when it was due, which may be some time before it starts
 */
record ClaimedCheck(MonitorSnapshot monitor, ProbeRequest request, Instant scheduledFor) {

    ClaimedCheck {
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(scheduledFor, "scheduledFor");
    }
}
