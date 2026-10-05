package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.monitoring.MonitorRecovered;
import io.github.ricardoord.opswatch.monitoring.MonitorWentDown;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorCheckRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSnapshot;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorState;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import io.github.ricardoord.opswatch.monitoring.domain.StateChange.Transition;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Keeps the result of a check and moves the state of its monitor
 * (docs/architecture/monitoring-engine.md#9-persistencia-del-resultado), in one short transaction that starts after the
 * request: no connection to the database is held while a target answers.
 *
 * <p>An error of OpsWatch is never a {@code DOWN} check: blaming the target would open a false incident. If the
 * transaction fails (the database, a listener of {@code incident} that throws), it all rolls back, without a check and
 * without a transition; it is logged with the id of the monitor and counted as {@code outcome="ERROR"}, and the next
 * check evaluates the state again.
 */
@Component
public class CheckResultRecorder {

    /** {@code opswatch_monitor_checks_total} in Prometheus (docs/architecture/monitoring-engine.md#14-métricas-del-motor). */
    static final String CHECKS = "opswatch.monitor.checks";

    static final String ERROR = "ERROR";
    static final String NO_REASON = "NONE";

    private static final Logger log = LoggerFactory.getLogger(CheckResultRecorder.class);

    private final MonitorStateRepository states;
    private final MonitorCheckRepository checks;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transactions;
    private final MeterRegistry meters;
    private final Clock clock;

    CheckResultRecorder(
            MonitorStateRepository states,
            MonitorCheckRepository checks,
            ApplicationEventPublisher events,
            TransactionOperations transactions,
            MeterRegistry meters,
            Clock clock) {
        this.states = states;
        this.checks = checks;
        this.events = events;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * Never throws: what it cannot keep, it logs and counts.
     *
     * @param startedAt when the request of the check started
     */
    public void record(MonitorSnapshot monitor, Instant startedAt, CheckOutcome outcome) {
        // PostgreSQL keeps microseconds, and checked_at is part of the primary key
        Instant checkedAt = startedAt.truncatedTo(ChronoUnit.MICROS);
        try {
            transactions.executeWithoutResult(transaction -> recordLocked(monitor, checkedAt, outcome));
        } catch (RuntimeException ex) {
            recordError(monitor.monitorId(), ex);
            return;
        }
        meters.counter(
                        CHECKS,
                        "outcome",
                        outcome.status().name(),
                        "reason",
                        outcome.failureReason() == null
                                ? NO_REASON
                                : outcome.failureReason().name())
                .increment();
    }

    /**
     * An error of OpsWatch instead of a result: of the engine before the request (decrypting the headers), of the
     * request itself, or of this recorder. No check and no transition, so the state stays as it was; logged with the id
     * of the monitor and counted as {@code outcome="ERROR"}.
     */
    public void recordError(UUID monitorId, RuntimeException cause) {
        log.atError()
                .addKeyValue("monitor.id", monitorId)
                .setCause(cause)
                .log("Check of monitor {} failed with an error of OpsWatch: no result recorded", monitorId);
        meters.counter(CHECKS, "outcome", ERROR, "reason", NO_REASON).increment();
    }

    /**
     * The row of the state, never that of the monitor: a check changes no setting and no version, and so never fails a
     * {@code PATCH}. Locking it first is the order every writer follows.
     */
    private void recordLocked(MonitorSnapshot monitor, Instant checkedAt, CheckOutcome outcome) {
        MonitorState state = states.findByIdForUpdate(monitor.monitorId())
                .orElseThrow(() -> new IllegalStateException("No state for monitor " + monitor.monitorId()));
        checks.insert(monitor.monitorId(), checkedAt, outcome);
        Instant statusSince = state.statusChangedAt();
        Transition transition = state.record(outcome, monitor.settings(), checkedAt, clock);
        switch (transition) {
            case WENT_DOWN ->
                events.publishEvent(new MonitorWentDown(
                        monitor.monitorId(),
                        monitor.organizationId(),
                        monitor.projectId(),
                        monitor.name(),
                        checkedAt,
                        Objects.requireNonNull(outcome.failureReason()),
                        outcome.httpStatus(),
                        state.consecutiveFailures()));
            case RECOVERED ->
                events.publishEvent(new MonitorRecovered(
                        monitor.monitorId(),
                        monitor.organizationId(),
                        monitor.projectId(),
                        monitor.name(),
                        checkedAt,
                        statusSince));
            case NONE -> {}
        }
    }
}
