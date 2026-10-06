package io.github.ricardoord.opswatch.incident.application;

import io.github.ricardoord.opswatch.incident.Resolution;
import io.github.ricardoord.opswatch.monitoring.MonitorDeleted;
import io.github.ricardoord.opswatch.monitoring.MonitorPaused;
import io.github.ricardoord.opswatch.monitoring.MonitorRecovered;
import io.github.ricardoord.opswatch.monitoring.MonitorWentDown;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The events of the monitor, heard synchronously, inside the transaction of the publisher (docs/architecture/events.md):
 * {@code monitoring} publishes them with the row of {@code monitor_state} locked, so the transitions of a monitor
 * arrive here one at a time and in order. If this throws, the publisher rolls back too.
 */
@Component
class MonitorEventsListener {

    private final IncidentLifecycle lifecycle;

    MonitorEventsListener(IncidentLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @EventListener
    void on(MonitorWentDown event) {
        lifecycle.open(event);
    }

    @EventListener
    void on(MonitorRecovered event) {
        lifecycle.resolve(event.monitorId(), Resolution.AUTO_RECOVERED, event.occurredAt(), null);
    }

    @EventListener
    void on(MonitorPaused event) {
        lifecycle.resolve(event.monitorId(), Resolution.MONITOR_PAUSED, event.occurredAt(), event.pausedBy());
    }

    @EventListener
    void on(MonitorDeleted event) {
        lifecycle.resolve(event.monitorId(), Resolution.MONITOR_DELETED, event.occurredAt(), event.deletedBy());
    }
}
