package io.github.ricardoord.opswatch.monitoring.domain;

/**
 * Where a monitor stands (docs/architecture/domain-model.md#monitorstate). The only place that says whether a monitor
 * is paused: there is no {@code enabled} flag that could contradict it.
 */
public enum MonitorStatus {
    /** Created or resumed, with no conclusive check yet. */
    PENDING,
    UP,
    /** Answers correctly, but slower than its degradation threshold. */
    DEGRADED,
    DOWN,
    /** Not scheduled until resumed. */
    PAUSED
}
