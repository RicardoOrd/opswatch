package io.github.ricardoord.opswatch.monitoring.engine;

/**
 * The names of the metrics of the engine (docs/devops/observability.md#métricas-propias), as Micrometer calls them;
 * Prometheus adds the unit and {@code _total}. Their only tags are {@code outcome}, from a closed set: never a monitor,
 * an organization or a URL, which would make Prometheus useless with thousands of monitors. The buckets of the
 * histograms are in application.yml ({@code management.metrics.distribution.slo}).
 */
final class EngineMetrics {

    /** {@code opswatch_scheduler_claim_duration_seconds}: what each claim costs PostgreSQL. */
    static final String CLAIM_DURATION = "opswatch.scheduler.claim.duration";

    /** {@code opswatch_scheduler_dispatcher_saturated_total}: dispatches with no permit free. */
    static final String DISPATCHER_SATURATED = "opswatch.scheduler.dispatcher.saturated";

    /** {@code opswatch_monitor_check_lag_seconds}: from when a check was due to when it started; the main signal. */
    static final String CHECK_LAG = "opswatch.monitor.check.lag";

    /** {@code opswatch_monitor_check_duration_seconds{outcome}}: the request of a check, as the target answered it. */
    static final String CHECK_DURATION = "opswatch.monitor.check.duration";

    /** {@code opswatch_monitor_checks_in_flight}: the permits of the semaphore in use. */
    static final String CHECKS_IN_FLIGHT = "opswatch.monitor.checks.in.flight";

    /** {@code opswatch_monitor_checks_overdue}: monitors due for longer than the threshold, the depth of the queue. */
    static final String CHECKS_OVERDUE = "opswatch.monitor.checks.overdue";

    /** The outcome of a check whose request failed in OpsWatch: as in {@code opswatch_monitor_checks_total}. */
    static final String ERROR = "ERROR";

    private EngineMetrics() {}
}
