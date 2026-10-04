package io.github.ricardoord.opswatch.monitoring.domain;

/**
 * The result of one check (docs/architecture/domain-model.md#monitorcheck). The status of the monitor follows from a
 * run of them, with its thresholds: one {@code DOWN} check does not make a {@code DOWN} monitor.
 */
public enum CheckStatus {
    UP,
    /** A correct answer, slower than the degradation threshold of the monitor. */
    DEGRADED,
    DOWN
}
