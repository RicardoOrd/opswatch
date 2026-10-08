package io.github.ricardoord.opswatch.notification.application;

import io.github.ricardoord.opswatch.incident.IncidentOpened;
import io.github.ricardoord.opswatch.incident.IncidentResolved;
import io.github.ricardoord.opswatch.notification.domain.DeliveryEventType;
import io.github.ricardoord.opswatch.notification.domain.DeliveryQueue;
import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns the incidents that opened or resolved into deliveries: one {@code PENDING} delivery for each enabled channel of
 * the organization that takes the incidents of the project (docs/architecture/events.md). Only adds to the queue;
 * {@code DeliveryWorker} sends.
 *
 * <p>After the commit of the incident, in a transaction of its own, with the publication in the registry: if the
 * application stops before this finishes, the next start runs it again. Delivery is at least once, and the unique key
 * of the deliveries makes a second run add nothing.
 *
 * <p>Renaming this class or its methods leaves the pending publications without a listener: Spring Modulith marks them
 * {@code FAILED} on the next start. Complete or migrate them first.
 */
@Component
@EnableConfigurationProperties(DeliveryProperties.class)
class IncidentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(IncidentEventsListener.class);

    private final DeliveryQueue queue;
    private final DeliveryProperties properties;
    private final IdGenerator ids;
    private final Clock clock;

    IncidentEventsListener(DeliveryQueue queue, DeliveryProperties properties, IdGenerator ids, Clock clock) {
        this.queue = queue;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(IncidentOpened event) {
        schedule(event.incidentId(), event.organizationId(), event.projectId(), DeliveryEventType.INCIDENT_OPENED);
    }

    @ApplicationModuleListener
    void on(IncidentResolved event) {
        schedule(event.incidentId(), event.organizationId(), event.projectId(), DeliveryEventType.INCIDENT_RESOLVED);
    }

    private void schedule(UUID incidentId, UUID organizationId, UUID projectId, DeliveryEventType eventType) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant dueAt = now.plus(properties.firstWait());
        int added = 0;
        for (UUID channel : queue.lockChannelsFor(organizationId, projectId)) {
            if (queue.addForIncident(ids.next(), channel, incidentId, eventType, now, dueAt)) {
                added++;
            }
        }
        log.atDebug()
                .addKeyValue("incident.id", incidentId)
                .log("{} deliveries of {} added for incident {}", added, eventType, incidentId);
    }
}
