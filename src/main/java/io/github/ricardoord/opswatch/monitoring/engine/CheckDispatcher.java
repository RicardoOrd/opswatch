package io.github.ricardoord.opswatch.monitoring.engine;

import io.github.ricardoord.opswatch.monitoring.application.CheckResultRecorder;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the checks that are due (docs/architecture/monitoring-engine.md#dispatcher): on every dispatch it claims as
 * many as it has permits free, and runs each one on a virtual thread of its own. The semaphore is the only limit of
 * the engine; the monitors it cannot take yet wait in the database, where they are due.
 *
 * <p>Each check: the request, out of any transaction, then {@link CheckEvaluator} and {@link CheckResultRecorder}. An
 * exception of the request is an error of OpsWatch, never a {@code DOWN} check.
 *
 * <p>On shutdown it stops claiming and waits for the checks in flight until the longest of their deadlines plus
 * {@code shutdown-grace}. A check still in flight after that is abandoned and its result is not recorded: the client
 * closes when the application stops, and a request cut that way would be a failure of ours counted as one of the
 * target, which could open a false incident.
 */
@Component
@ConditionalOnBooleanProperty(name = "opswatch.monitoring.engine.enabled", matchIfMissing = true)
class CheckDispatcher implements SmartLifecycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CheckDispatcher.class);

    private final CheckClaimer claimer;
    private final HttpMonitorClient client;
    private final CheckResultRecorder recorder;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Semaphore permits;
    private final int maxConcurrentChecks;
    private final int maxBatchSize;
    private final Duration deadlineGrace;
    private final Duration shutdownGrace;

    /** Dispatching, starting and stopping take it, so a dispatch is never halfway when it stops. */
    private final Object lock = new Object();

    private boolean running;

    /**
     * One per start. Shut down only when no check of its own is left in flight, or when the shutdown gave up waiting:
     * a check that finds it shut down has been abandoned.
     */
    private @Nullable ExecutorService checks;

    /** {@link System#nanoTime()} by which every check in flight has reached its deadline. */
    private long inFlightUntil;

    /** While it runs: in a registry shared by several dispatchers (the tests), a gauge left behind would stay stale. */
    private @Nullable Gauge inFlightGauge;

    CheckDispatcher(
            CheckClaimer claimer,
            HttpMonitorClient client,
            CheckResultRecorder recorder,
            MonitoringEngineProperties properties,
            MeterRegistry meters,
            Clock clock) {
        this.claimer = claimer;
        this.client = client;
        this.recorder = recorder;
        this.meters = meters;
        this.clock = clock;
        this.maxConcurrentChecks = properties.maxConcurrentChecks();
        this.permits = new Semaphore(maxConcurrentChecks);
        this.maxBatchSize = properties.maxBatchSize();
        this.deadlineGrace = properties.deadlineGrace();
        this.shutdownGrace = properties.shutdownGrace();
        // At zero from the start, so that a rate over it has a series to work on
        meters.counter(EngineMetrics.DISPATCHER_SATURATED);
    }

    @Scheduled(fixedDelayString = "${opswatch.monitoring.engine.dispatch-interval:1s}")
    void scheduled() {
        dispatch();
    }

    /**
     * Never claims what it cannot start now: with no permit free, it claims nothing.
     *
     * @return how many checks it started
     */
    int dispatch() {
        synchronized (lock) {
            ExecutorService executor = checks;
            int free = permits.availablePermits();
            if (!running || executor == null) {
                return 0;
            }
            if (free == 0) {
                // Back pressure: what is due waits in the database, and the lag shows it
                meters.counter(EngineMetrics.DISPATCHER_SATURATED).increment();
                return 0;
            }
            List<ClaimedCheck> claimed;
            try {
                claimed = claimer.claim(Math.min(free, maxBatchSize));
            } catch (RuntimeException ex) {
                // The database, most likely: what is due stays due, and the next dispatch tries again
                log.atError().setCause(ex).log("Could not claim the checks that are due");
                return 0;
            }
            for (ClaimedCheck check : claimed) {
                // Never waits: only this method acquires, under the lock, and it claimed no more than were free
                permits.acquireUninterruptibly();
                long deadline = System.nanoTime()
                        + check.request().timeout().plus(deadlineGrace).toNanos();
                if (deadline - inFlightUntil > 0) {
                    inFlightUntil = deadline;
                }
                executor.execute(() -> {
                    try {
                        run(check, executor);
                    } finally {
                        permits.release();
                    }
                });
            }
            return claimed.size();
        }
    }

    /** Checks running now, abandoned ones included until they end. */
    int inFlight() {
        return maxConcurrentChecks - permits.availablePermits();
    }

    private void run(ClaimedCheck check, ExecutorService executor) {
        MonitorSnapshot monitor = check.monitor();
        Instant startedAt = clock.instant();
        Duration late = Duration.between(check.scheduledFor(), startedAt);
        meters.timer(EngineMetrics.CHECK_LAG).record(late.isNegative() ? Duration.ZERO : late);
        long probing = System.nanoTime();
        CheckOutcome outcome;
        try {
            outcome = CheckEvaluator.evaluate(monitor.settings(), client.probe(check.request()));
        } catch (RuntimeException ex) {
            if (!executor.isShutdown()) {
                timeProbe(EngineMetrics.ERROR, probing);
                recorder.recordError(monitor.monitorId(), ex);
            }
            return;
        }
        if (executor.isShutdown()) {
            log.atDebug()
                    .addKeyValue("monitor.id", monitor.monitorId())
                    .log("Result of a check of monitor {} dropped: abandoned at shutdown", monitor.monitorId());
            return;
        }
        timeProbe(outcome.status().name(), probing);
        recorder.record(monitor, startedAt, outcome);
    }

    private void timeProbe(String outcome, long startedNanos) {
        meters.timer(EngineMetrics.CHECK_DURATION, "outcome", outcome)
                .record(Duration.ofNanos(System.nanoTime() - startedNanos));
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            checks = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("check-", 0).factory());
            inFlightUntil = System.nanoTime();
            inFlightGauge = Gauge.builder(EngineMetrics.CHECKS_IN_FLIGHT, this, CheckDispatcher::inFlight)
                    .strongReference(true)
                    .register(meters);
            running = true;
        }
    }

    /** Waits on a thread of its own, so that the rest of the application stops meanwhile. */
    @Override
    public void stop(Runnable callback) {
        Shutdown shutdown = stopClaiming();
        if (shutdown == null) {
            callback.run();
            return;
        }
        Thread.ofVirtual().name("check-dispatcher-shutdown").start(() -> {
            try {
                awaitInFlight(shutdown);
            } finally {
                callback.run();
            }
        });
    }

    @Override
    public void stop() {
        Shutdown shutdown = stopClaiming();
        if (shutdown != null) {
            awaitInFlight(shutdown);
        }
    }

    private @Nullable Shutdown stopClaiming() {
        synchronized (lock) {
            if (!running || checks == null) {
                return null;
            }
            running = false;
            return new Shutdown(checks, inFlightUntil + shutdownGrace.toNanos());
        }
    }

    private void awaitInFlight(Shutdown shutdown) {
        boolean finished;
        try {
            // Every permit back: no check in flight
            finished = permits.tryAcquire(
                    maxConcurrentChecks, Math.max(0, shutdown.until() - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (finished) {
            permits.release(maxConcurrentChecks);
        } else {
            log.atWarn()
                    .log(
                            "{} checks still in flight at shutdown are abandoned: their results are not recorded",
                            inFlight());
        }
        shutdown.executor().shutdown();
        unregisterInFlight();
    }

    /**
     * Spring destroys this before the client it depends on. If the shutdown phase ran out of time while this was still
     * waiting, the checks in flight are abandoned here, before the client closes under them.
     */
    @Override
    public void destroy() {
        ExecutorService executor;
        synchronized (lock) {
            running = false;
            executor = checks;
        }
        if (executor != null) {
            executor.shutdown();
        }
        unregisterInFlight();
    }

    private void unregisterInFlight() {
        Gauge gauge;
        synchronized (lock) {
            gauge = inFlightGauge;
            inFlightGauge = null;
        }
        if (gauge != null) {
            meters.remove(gauge);
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    /** That of the graceful shutdown of the web server: both wait at once, within one shutdown phase. */
    @Override
    public int getPhase() {
        return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE;
    }

    /** @param until {@link System#nanoTime()} until which it waits for the checks in flight */
    private record Shutdown(ExecutorService executor, long until) {}
}
