package io.github.ricardoord.opswatch.incident.application;

import io.github.ricardoord.opswatch.incident.IncidentOpened;
import io.github.ricardoord.opswatch.incident.IncidentResolved;
import io.github.ricardoord.opswatch.incident.Resolution;
import io.github.ricardoord.opswatch.incident.domain.Incident;
import io.github.ricardoord.opswatch.incident.domain.IncidentRepository;
import io.github.ricardoord.opswatch.incident.domain.IncidentTimelineRepository;
import io.github.ricardoord.opswatch.incident.domain.NewIncident;
import io.github.ricardoord.opswatch.incident.domain.NewIncidentRepository;
import io.github.ricardoord.opswatch.incident.domain.TimelineEntryType;
import io.github.ricardoord.opswatch.monitoring.MonitorWentDown;
import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Opens and resolves incidents (docs/architecture/incident-lifecycle.md#2-reglas-de-apertura-y-cierre), always inside the
 * transaction that moved the state of the monitor, which holds the row of {@code monitor_state}: the invariant "a
 * {@code DOWN} monitor has exactly one active incident" is kept atomically. No external I/O here: whatever throws rolls
 * that transaction back, the check included.
 */
@Component
class IncidentLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IncidentLifecycle.class);

    private final IncidentRepository incidents;
    private final NewIncidentRepository openings;
    private final IncidentTimelineRepository timeline;
    private final ApplicationEventPublisher events;
    private final IncidentMetrics metrics;
    private final IdGenerator ids;
    private final Clock clock;

    IncidentLifecycle(
            IncidentRepository incidents,
            NewIncidentRepository openings,
            IncidentTimelineRepository timeline,
            ApplicationEventPublisher events,
            IncidentMetrics metrics,
            IdGenerator ids,
            Clock clock) {
        this.incidents = incidents;
        this.openings = openings;
        this.timeline = timeline;
        this.events = events;
        this.metrics = metrics;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Opens an incident unless the monitor already has an active one (rules R1 and R2): a repeated event opens nothing.
     * Publishes {@link IncidentOpened} in the same transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void open(MonitorWentDown down) {
        Instant openedAt = down.occurredAt().truncatedTo(ChronoUnit.MICROS);
        NewIncident incident = new NewIncident(
                ids.next(),
                down.organizationId(),
                down.projectId(),
                down.monitorId(),
                down.monitorName(),
                down.cause().name(),
                down.httpStatus(),
                openedAt);
        if (!openings.insertIfNoneActive(incident, now())) {
            log.atDebug()
                    .addKeyValue("monitor.id", down.monitorId())
                    .log("Monitor {} went down with an incident already active: none opened", down.monitorId());
            return;
        }
        timeline.append(ids.next(), incident.id(), TimelineEntryType.OPENED, null, openedAt, null);
        events.publishEvent(new IncidentOpened(
                incident.id(),
                incident.organizationId(),
                incident.projectId(),
                incident.monitorId(),
                incident.monitorName(),
                openedAt,
                incident.cause(),
                incident.causeHttpStatus()));
        afterCommit(() -> {
            metrics.opened();
            log.atInfo()
                    .addKeyValue("event.action", "incident.opened")
                    .addKeyValue("incident.id", incident.id())
                    .addKeyValue("monitor.id", incident.monitorId())
                    .log(
                            "Incident {} opened: monitor {} is down ({})",
                            incident.id(),
                            incident.monitorId(),
                            incident.cause());
        });
    }

    /**
     * Resolves the active incident of the monitor, if it has one (rules R4 and R5): pausing a monitor that is up, or
     * deleting one that never went down, resolves nothing. Publishes {@link IncidentResolved} in the same transaction.
     *
     * @param at when it happened: the start of the check that recovered the monitor, or the pause or the deletion
     * @param by who paused or deleted the monitor; null for a recovery
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(UUID monitorId, Resolution resolution, Instant at, @Nullable UUID by) {
        Optional<Incident> active = incidents.findActiveByMonitorIdForUpdate(monitorId);
        if (active.isEmpty()) {
            return;
        }
        Incident incident = active.get();
        incident.resolve(resolution, at, by, clock);
        Instant resolvedAt = Objects.requireNonNull(incident.resolvedAt());
        timeline.append(ids.next(), incident.id(), TimelineEntryType.RESOLVED, by, resolvedAt, null);
        events.publishEvent(new IncidentResolved(
                incident.id(),
                incident.organizationId(),
                incident.projectId(),
                incident.monitorId(),
                incident.monitorName(),
                incident.openedAt(),
                resolvedAt,
                resolution));
        afterCommit(() -> log.atInfo()
                .addKeyValue("event.action", "incident.resolved")
                .addKeyValue("incident.id", incident.id())
                .addKeyValue("monitor.id", monitorId)
                .log("Incident {} resolved: {}", incident.id(), resolution));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** What only makes sense if the transaction commits: a rolled-back check opened and resolved nothing. */
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
