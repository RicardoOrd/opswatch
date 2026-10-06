package io.github.ricardoord.opswatch.incident.domain;

import io.github.ricardoord.opswatch.incident.Resolution;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A continuous period in which a monitor was {@code DOWN} (docs/architecture/domain-model.md#incident). It opens with
 * the transition to {@code DOWN} ({@link NewIncidentRepository}) and resolves when the monitor recovers, or is paused
 * or deleted. Its rules are in docs/architecture/incident-lifecycle.md.
 *
 * <p>Whoever changes an active incident reads it with {@code FOR UPDATE} ({@link IncidentRepository}): the resolution,
 * inside the transaction that moves the state of the monitor, and the acknowledgement (OW-033) serialize on the row, so
 * neither has to fail and roll back a check.
 */
@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    private UUID id;

    private UUID organizationId;

    private UUID projectId;

    private UUID monitorId;

    private String monitorName;

    @Enumerated(EnumType.STRING)
    private IncidentStatus status;

    private String cause;

    private @Nullable Short causeHttpStatus;

    private Instant openedAt;

    private @Nullable Instant acknowledgedAt;

    /** Null once that user's account is deleted. */
    private @Nullable UUID acknowledgedBy;

    private @Nullable Instant resolvedAt;

    /** Who paused or deleted the monitor; null for a recovery, and once that user's account is deleted. */
    private @Nullable UUID resolvedBy;

    @Enumerated(EnumType.STRING)
    private @Nullable Resolution resolution;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private @Nullable Long version;

    /** For JPA, which populates the fields. Incidents are opened by {@link NewIncidentRepository}. */
    protected Incident() {}

    /**
     * Final: a monitor that goes down again opens a new incident.
     *
     * @param at when it happened: the start of the check that recovered the monitor, or the pause or the deletion
     * @param by who paused or deleted the monitor; null for a recovery
     * @throws IllegalStateException if it was already resolved
     */
    public void resolve(Resolution resolution, Instant at, @Nullable UUID by, Clock clock) {
        if (status == IncidentStatus.RESOLVED) {
            throw new IllegalStateException("incident " + id + " is already resolved");
        }
        this.status = IncidentStatus.RESOLVED;
        this.resolution = resolution;
        this.resolvedAt = at.truncatedTo(ChronoUnit.MICROS);
        this.resolvedBy = by;
        this.updatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID monitorId() {
        return monitorId;
    }

    public String monitorName() {
        return monitorName;
    }

    public IncidentStatus status() {
        return status;
    }

    public String cause() {
        return cause;
    }

    public @Nullable Integer causeHttpStatus() {
        return causeHttpStatus == null ? null : causeHttpStatus.intValue();
    }

    public Instant openedAt() {
        return openedAt;
    }

    public @Nullable Instant acknowledgedAt() {
        return acknowledgedAt;
    }

    public @Nullable UUID acknowledgedBy() {
        return acknowledgedBy;
    }

    public @Nullable Instant resolvedAt() {
        return resolvedAt;
    }

    public @Nullable UUID resolvedBy() {
        return resolvedBy;
    }

    public @Nullable Resolution resolution() {
        return resolution;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Not {@code version()}: Spring Data reads an accessor of that name to tell whether the entity is new.
     *
     * @return null only for an incident not read from the database
     */
    public @Nullable Long savedVersion() {
        return version;
    }
}
