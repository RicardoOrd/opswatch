package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.organization.ProjectDeleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Deletes the monitors of a deleted project (docs/architecture/events.md). After the commit of the deletion, in a
 * transaction of its own, with the publication in the registry: if the application stops before this finishes, the
 * next start runs it again. Delivery is at least once, and a second run finds no monitor left to delete.
 *
 * <p>Renaming this class or its method leaves the pending publications without a listener: Spring Modulith marks them
 * {@code FAILED} on the next start. Complete or migrate them first.
 */
@Component
class ProjectDeletedListener {

    private final MonitorService monitors;

    ProjectDeletedListener(MonitorService monitors) {
        this.monitors = monitors;
    }

    @ApplicationModuleListener
    void on(ProjectDeleted event) {
        monitors.deleteAllOf(event.projectId(), event.deletedBy());
    }
}
