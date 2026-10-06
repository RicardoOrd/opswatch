package io.github.ricardoord.opswatch.notification.application;

import io.github.ricardoord.opswatch.notification.domain.NotificationChannelRepository;
import io.github.ricardoord.opswatch.organization.OrganizationDeleted;
import io.github.ricardoord.opswatch.organization.ProjectDeleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Deletes the channels of a deleted project or organization, with their deliveries (docs/architecture/events.md). After
 * the commit of the deletion, in a transaction of its own, with the publication in the registry: if the application
 * stops before this finishes, the next start runs it again. Delivery is at least once, and a second run finds nothing
 * left to delete.
 *
 * <p>Renaming this class or its methods leaves the pending publications without a listener: Spring Modulith marks them
 * {@code FAILED} on the next start. Complete or migrate them first.
 */
@Component
class ChannelCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(ChannelCleanupListener.class);

    private final NotificationChannelRepository channels;

    ChannelCleanupListener(NotificationChannelRepository channels) {
        this.channels = channels;
    }

    /** The channels limited to the project; those of every project stay. */
    @ApplicationModuleListener
    void on(ProjectDeleted event) {
        int deleted = channels.deleteByProjectId(event.projectId());
        log.atDebug()
                .addKeyValue("project.id", event.projectId())
                .log("Deleted {} channels of deleted project {}", deleted, event.projectId());
    }

    /**
     * Every channel of the organization: those of every project would otherwise still be told about the incidents
     * that the deletion of its monitors resolves.
     */
    @ApplicationModuleListener
    void on(OrganizationDeleted event) {
        int deleted = channels.deleteByOrganizationId(event.organizationId());
        log.atDebug()
                .addKeyValue("organization.id", event.organizationId())
                .log("Deleted {} channels of deleted organization {}", deleted, event.organizationId());
    }
}
