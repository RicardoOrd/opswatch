package io.github.ricardoord.opswatch.shared.events;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the completed event publications archived more than the retention ago (docs/database/data-retention.md).
 *
 * <p>Only the archive: with {@code spring.modulith.events.completion-mode=archive}, Spring Modulith 2.1.1 runs
 * {@link CompletedEventPublications#deletePublicationsOlderThan} against {@code event_publication_archive} and only
 * for completed rows. A pending publication in {@code event_publication} is work not done yet, so nothing here
 * deletes it, however old ({@code EventPublicationPurgeJobIT} keeps checking that after every upgrade).
 *
 * <p>No lock between instances: deleting is idempotent, so two instances running it at once only share the work.
 */
@Component
@EnableConfigurationProperties(EventPublicationRetentionProperties.class)
public class EventPublicationPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationPurgeJob.class);

    private final CompletedEventPublications completed;
    private final EventPublicationRetentionProperties properties;
    private final Clock clock;

    public EventPublicationPurgeJob(
            CompletedEventPublications completed, EventPublicationRetentionProperties properties, Clock clock) {
        this.completed = completed;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${opswatch.retention.cron:0 30 3 * * *}", zone = "UTC")
    void scheduled() {
        purge();
    }

    public void purge() {
        // Spring Modulith takes the cutoff from the same Clock bean
        Instant cutoff = clock.instant().minus(properties.eventPublications());
        completed.deletePublicationsOlderThan(properties.eventPublications());
        log.info("Purged the event publications archived before {}", cutoff);
    }
}
