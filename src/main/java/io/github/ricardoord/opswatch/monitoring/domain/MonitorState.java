package io.github.ricardoord.opswatch.monitoring.domain;

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
 *
 * <p>The columns of the last check result that nothing reads yet arrive with the engine (v0.3.0).
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

    private @Nullable Short lastHttpStatus;

    private @Nullable Integer lastResponseTimeMs;

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
