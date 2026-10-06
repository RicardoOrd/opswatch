package io.github.ricardoord.opswatch.incident;

/**
 * How an incident was resolved (docs/architecture/incident-lifecycle.md). Never by hand in V1: an incident reflects its
 * monitor. The names are stored as they are in {@code incidents.resolution}: renaming one needs a migration.
 */
public enum Resolution {
    /** The monitor reached its recovery threshold. */
    AUTO_RECOVERED,
    /** Someone paused the monitor. */
    MONITOR_PAUSED,
    /** Someone deleted the monitor, or its project. */
    MONITOR_DELETED
}
