package io.github.ricardoord.opswatch.monitoring.engine;

/**
 * Runs the request of a check (docs/architecture/monitoring-engine.md#5-httpmonitorclient). It only observes: whether
 * the monitor is {@code UP}, {@code DEGRADED} or {@code DOWN} is for the domain to decide. The engine is tested with a
 * fake one; the real one, against simulated targets.
 */
public interface HttpMonitorClient {

    /**
     * Never longer than the timeout of the request plus a small margin, whatever the target does, except while the
     * system resolves a name: that cannot be interrupted (docs/architecture/monitoring-engine.md#dns).
     *
     * @return what happened on the network; a failure of the target is an observation, not an exception
     * @throws RuntimeException only for an error of OpsWatch, which is not a check and must not change the state
     */
    HttpObservation probe(ProbeRequest request);
}
