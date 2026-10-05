package io.github.ricardoord.opswatch.monitoring.domain;

/** The status and counters of a monitor after a check, and whether that crossed a threshold. */
public record StateChange(
        MonitorStatus status, int consecutiveFailures, int consecutiveSuccesses, Transition transition) {

    /** What the change announces: {@code UP} and {@code DEGRADED} switch silently, and wake nobody up. */
    public enum Transition {
        NONE,
        /** Announced with {@code MonitorWentDown}: it opens an incident. */
        WENT_DOWN,
        /** Announced with {@code MonitorRecovered}: it resolves the incident. */
        RECOVERED
    }
}
