package io.github.ricardoord.opswatch.monitoring.domain;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.domain.StateChange.Transition;
import io.github.ricardoord.opswatch.shared.error.ConflictException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * The execution state of a monitor (docs/architecture/domain-model.md#monitorstate). Only {@code monitoring} writes it,
 * always with the row locked ({@code SELECT … FOR UPDATE}): there is no version, because the engine writes it on every
 * check and a version would make it fail against every other writer.
 */
@Entity
@Table(name = "monitor_state")
public class MonitorState implements Persistable<UUID> {

    @Id
    private UUID monitorId;

    @Enumerated(EnumType.STRING)
    private MonitorStatus status;

    private Instant statusChangedAt;

    private int consecutiveFailures;

    private int consecutiveSuccesses;

    private @Nullable Instant lastCheckedAt;

    @Enumerated(EnumType.STRING)
    private @Nullable CheckStatus lastCheckStatus;

    private @Nullable Short lastHttpStatus;

    private @Nullable Integer lastResponseTimeMs;

    @Enumerated(EnumType.STRING)
    private @Nullable FailureReason lastFailureReason;

    /** Null when not scheduled: paused or deleted. */
    private @Nullable Instant nextCheckAt;

    private Instant updatedAt;

    /** The table has no version column: this is how Spring Data knows to insert without a {@code SELECT} first. */
    @Transient
    private boolean isNew;

    /** For JPA, which populates the fields. */
    protected MonitorState() {}

    private MonitorState(UUID monitorId, Instant now, Instant nextCheckAt) {
        this.monitorId = monitorId;
        this.status = MonitorStatus.PENDING;
        this.statusChangedAt = now;
        this.nextCheckAt = nextCheckAt;
        this.updatedAt = now;
        this.isNew = true;
    }

    /**
     * The state of a new monitor: {@code PENDING}, with its first check {@code jitter} from now so that monitors created
     * together do not run together forever (docs/architecture/monitoring-engine.md#algoritmo-de-programación).
     */
    public static MonitorState pending(UUID monitorId, Duration jitter, Clock clock) {
        Instant now = now(clock);
        return new MonitorState(monitorId, now, now.plus(jitter).truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * A new interval takes effect at once if it is shorter than the wait left: {@code next_check_at = min(next_check_at,
     * now + interval)}. A paused monitor stays unscheduled: only resuming schedules it, and a paused state with a next
     * check breaks {@code ck_monitor_state_paused}.
     */
    public void intervalChanged(int intervalSeconds, Clock clock) {
        if (status == MonitorStatus.PAUSED || nextCheckAt == null) {
            return;
        }
        Instant now = now(clock);
        Instant byNewInterval = now.plusSeconds(intervalSeconds);
        if (byNewInterval.isBefore(nextCheckAt)) {
            this.nextCheckAt = byNewInterval;
            this.updatedAt = now;
        }
    }

    /**
     * {@code PAUSED} and unscheduled, with the counters back to zero
     * (docs/architecture/monitoring-engine.md#pausa-reanudación-borrado-y-edición). A check already in flight is saved
     * when it comes back, but it no longer changes the state.
     *
     * @throws ConflictException if it is already paused (409)
     */
    public void pause(Clock clock) {
        if (status == MonitorStatus.PAUSED) {
            throw new ConflictException("The monitor is already paused.");
        }
        stop(clock);
    }

    /**
     * {@code PENDING} again, with the counters back to zero and the first check {@code jitter} from now, as a new monitor.
     *
     * @throws ConflictException if it is not paused (409)
     */
    public void resume(Duration jitter, Clock clock) {
        if (status != MonitorStatus.PAUSED) {
            throw new ConflictException("The monitor is not paused.");
        }
        Instant now = now(clock);
        this.status = MonitorStatus.PENDING;
        this.statusChangedAt = now;
        this.nextCheckAt = now.plus(jitter).truncatedTo(ChronoUnit.MICROS);
        resetCounters(now);
    }

    /** The state of a deleted monitor: as a pause, whatever it was before, so that it never runs again. */
    public void stop(Clock clock) {
        Instant now = now(clock);
        if (status != MonitorStatus.PAUSED) {
            this.status = MonitorStatus.PAUSED;
            this.statusChangedAt = now;
        }
        this.nextCheckAt = null;
        resetCounters(now);
    }

    /**
     * The result of a check: it becomes the last one, and moves the status with {@link StateTransition}. Two results
     * change nothing, although they are kept as checks:
     *
     * <ul>
     *   <li>that of a paused or deleted monitor, which was in flight when it stopped;
     *   <li>that of a check that started before the current status: one in flight across a pause and a resume, which
     *       belongs to the monitor before it was resumed.
     * </ul>
     *
     * @param checkedAt when the check started
     * @return what to announce
     */
    public Transition record(CheckOutcome outcome, MonitorSettings settings, Instant checkedAt, Clock clock) {
        if (status == MonitorStatus.PAUSED || checkedAt.isBefore(statusChangedAt)) {
            return Transition.NONE;
        }
        StateChange change =
                StateTransition.apply(status, consecutiveFailures, consecutiveSuccesses, outcome.status(), settings);
        if (change.status() != status) {
            this.status = change.status();
            this.statusChangedAt = checkedAt;
        }
        this.consecutiveFailures = change.consecutiveFailures();
        this.consecutiveSuccesses = change.consecutiveSuccesses();
        this.lastCheckedAt = checkedAt;
        this.lastCheckStatus = outcome.status();
        this.lastHttpStatus =
                outcome.httpStatus() == null ? null : outcome.httpStatus().shortValue();
        this.lastResponseTimeMs = outcome.responseTimeMs();
        this.lastFailureReason = outcome.failureReason();
        this.updatedAt = now(clock);
        return change.transition();
    }

    private void resetCounters(Instant now) {
        this.consecutiveFailures = 0;
        this.consecutiveSuccesses = 0;
        this.updatedAt = now;
    }

    /** PostgreSQL keeps microseconds: truncating here makes the returned value match what a later read returns. */
    private static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public UUID getId() {
        return monitorId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        isNew = false;
    }

    public UUID monitorId() {
        return monitorId;
    }

    public MonitorStatus status() {
        return status;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public int consecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public @Nullable Instant lastCheckedAt() {
        return lastCheckedAt;
    }

    public @Nullable CheckStatus lastCheckStatus() {
        return lastCheckStatus;
    }

    public @Nullable FailureReason lastFailureReason() {
        return lastFailureReason;
    }

    public @Nullable Integer lastHttpStatus() {
        return lastHttpStatus == null ? null : lastHttpStatus.intValue();
    }

    public @Nullable Integer lastResponseTimeMs() {
        return lastResponseTimeMs;
    }

    public @Nullable Instant nextCheckAt() {
        return nextCheckAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof MonitorState state && monitorId.equals(state.monitorId()));
    }

    @Override
    public int hashCode() {
        return monitorId.hashCode();
    }

    @Override
    public String toString() {
        return "MonitorState[monitorId=" + monitorId + ", status=" + status + "]";
    }
}
