package io.github.ricardoord.opswatch.incident.application;

import io.github.ricardoord.opswatch.identity.UserDirectory;
import io.github.ricardoord.opswatch.identity.UserSummary;
import io.github.ricardoord.opswatch.incident.IncidentAcknowledged;
import io.github.ricardoord.opswatch.incident.domain.Incident;
import io.github.ricardoord.opswatch.incident.domain.IncidentFilter;
import io.github.ricardoord.opswatch.incident.domain.IncidentRepository;
import io.github.ricardoord.opswatch.incident.domain.IncidentTimelineRepository;
import io.github.ricardoord.opswatch.incident.domain.TimelineEntry;
import io.github.ricardoord.opswatch.incident.domain.TimelineEntryType;
import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.shared.error.BusinessRuleViolationException;
import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import io.github.ricardoord.opswatch.shared.error.PermissionDeniedException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * What people do with incidents: list them, read them and acknowledge them
 * (docs/architecture/incident-lifecycle.md#2-reglas-de-apertura-y-cierre). There is no manual resolution in V1.
 *
 * <p>An incident is authorized through its organization, never its project: the incidents of a deleted project or
 * monitor stay visible as history. A non-member gets the 404 of a missing incident, never one that names its
 * organization.
 */
@Service
public class IncidentService {

    private final IncidentRepository incidents;
    private final IncidentTimelineRepository timeline;
    private final AccessControl access;
    private final UserDirectory users;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transactions;
    private final IdGenerator ids;
    private final Clock clock;

    IncidentService(
            IncidentRepository incidents,
            IncidentTimelineRepository timeline,
            AccessControl access,
            UserDirectory users,
            ApplicationEventPublisher events,
            TransactionOperations transactions,
            IdGenerator ids,
            Clock clock) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.access = access;
        this.users = users;
        this.events = events;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @throws ResourceNotFoundException if the organization is missing or deleted, or the user is not a member (404)
     * @throws InvalidParameterException if {@code from} is not before {@code to} (400)
     */
    @Transactional(readOnly = true)
    public Page<Incident> listOf(UUID userId, UUID organizationId, IncidentFilter filter, Pageable pageable) {
        access.require(userId, organizationId, Permission.INCIDENT_READ);
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw new InvalidParameterException("'from' must be before 'to'.");
        }
        return incidents.findAll(filter.of(organizationId), pageable);
    }

    /** @throws ResourceNotFoundException if it is missing, or the user is not a member of its organization (404) */
    @Transactional(readOnly = true)
    public IncidentDetail get(UUID userId, UUID incidentId) {
        return detailOf(authorized(userId, incidentId, Permission.INCIDENT_READ, incidents.findById(incidentId)));
    }

    /**
     * Rule R7. Authorized before taking the lock, so that someone outside the organization never holds it, and again
     * with it taken. The lock is the one the resolution takes inside the transaction of the check: whichever comes
     * second waits for the first, and neither fails (docs/architecture/incident-lifecycle.md#6-concurrencia-y-casos-límite).
     *
     * @param note already checked by the request; null for none
     * @throws ResourceNotFoundException if it is missing, or the user is not a member of its organization (404)
     * @throws PermissionDeniedException if the user's role does not allow it (403)
     * @throws BusinessRuleViolationException if it is not {@code OPEN}, also because it was just resolved (409)
     */
    public IncidentDetail acknowledge(UUID userId, UUID incidentId, @Nullable String note) {
        authorized(userId, incidentId, Permission.INCIDENT_ACKNOWLEDGE, incidents.findById(incidentId));
        return Objects.requireNonNull(transactions.execute(transaction -> {
            Incident incident = authorized(
                    userId, incidentId, Permission.INCIDENT_ACKNOWLEDGE, incidents.findByIdForUpdate(incidentId));
            incident.acknowledge(userId, clock);
            // The new version, for the response
            incidents.flush();
            Instant acknowledgedAt = Objects.requireNonNull(incident.acknowledgedAt());
            timeline.append(ids.next(), incident.id(), TimelineEntryType.ACKNOWLEDGED, userId, acknowledgedAt, note);
            events.publishEvent(new IncidentAcknowledged(
                    incident.id(),
                    incident.organizationId(),
                    incident.projectId(),
                    incident.monitorId(),
                    acknowledgedAt,
                    userId));
            return detailOf(incident);
        }));
    }

    private Incident authorized(UUID userId, UUID incidentId, Permission permission, Optional<Incident> found) {
        Incident incident = found.orElseThrow(() -> new ResourceNotFoundException("incident", incidentId));
        try {
            access.require(userId, incident.organizationId(), permission);
        } catch (ResourceNotFoundException ex) {
            throw new ResourceNotFoundException("incident", incidentId);
        }
        return incident;
    }

    /** The people of the timeline, by name, in one query. */
    private IncidentDetail detailOf(Incident incident) {
        List<TimelineEntry> entries = timeline.findByIncident(incident.id());
        Set<UUID> people = new HashSet<>();
        if (incident.acknowledgedBy() != null) {
            people.add(incident.acknowledgedBy());
        }
        for (TimelineEntry entry : entries) {
            if (entry.actor() != null) {
                people.add(entry.actor());
            }
        }
        Map<UUID, UserSummary> named = people.isEmpty() ? Map.of() : users.findAllById(people);
        return new IncidentDetail(
                incident,
                actor(incident.acknowledgedBy(), named),
                entries.stream()
                        .map(entry -> new IncidentDetail.Entry(
                                entry.type(), entry.occurredAt(), actor(entry.actor(), named), entry.note()))
                        .toList());
    }

    /** Null when nobody did it, or the account is gone. */
    private static IncidentDetail.@Nullable Actor actor(@Nullable UUID userId, Map<UUID, UserSummary> named) {
        if (userId == null) {
            return null;
        }
        UserSummary user = named.get(userId);
        return user == null ? null : new IncidentDetail.Actor(user.id(), user.displayName());
    }
}
