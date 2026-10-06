package io.github.ricardoord.opswatch.incident.domain;

/**
 * Where an incident is in its lifecycle (docs/architecture/incident-lifecycle.md#3-estados). {@code OPEN} and
 * {@code ACKNOWLEDGED} are active: a monitor has at most one active incident. The names are stored as they are in
 * {@code incidents.status}.
 */
public enum IncidentStatus {
    OPEN,
    /** Someone is looking into it; it still resolves on its own. */
    ACKNOWLEDGED,
    /** Final: if the monitor goes down again, a new incident opens. */
    RESOLVED
}
