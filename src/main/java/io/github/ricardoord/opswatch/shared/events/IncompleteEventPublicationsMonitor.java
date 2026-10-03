package io.github.ricardoord.opswatch.shared.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code opswatch_event_publications_incomplete} up to date and warns about publications pending for too long:
 * a listener that keeps failing leaves its publication pending, and nothing else would show it
 * (docs/architecture/events.md#6-problemas-de-consistencia-y-cómo-se-tratan).
 *
 * <p>The count runs on a schedule, never on a scrape (docs/devops/observability.md). Every instance runs it and
 * reports the same database-wide figure. Read only: the warning carries counts and dates, never an event payload.
 */
@Component
@EnableConfigurationProperties(EventPublicationMonitorProperties.class)
public class IncompleteEventPublicationsMonitor {

    static final String GAUGE = "opswatch.event.publications.incomplete";

    private static final Logger log = LoggerFactory.getLogger(IncompleteEventPublicationsMonitor.class);

    private final JdbcClient jdbc;
    private final EventPublicationMonitorProperties properties;
    private final Clock clock;
    private final AtomicLong incomplete = new AtomicLong();

    public IncompleteEventPublicationsMonitor(
            JdbcClient jdbc, EventPublicationMonitorProperties properties, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        Gauge.builder(GAUGE, incomplete, AtomicLong::get)
                .description("Event publications whose listener has not completed yet")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${opswatch.events.incomplete-check-interval:30s}")
    void scheduled() {
        check();
    }

    /** @return what it found, also published as the gauge */
    public Pending check() {
        Instant alertBefore = clock.instant().minus(properties.incompleteAlertAfter());
        Pending pending = jdbc.sql("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE publication_date < :alertBefore) AS overdue,
                               min(publication_date) AS oldest
                        FROM event_publication
                        WHERE completion_date IS NULL""")
                // OffsetDateTime: the JDBC 4.2 type of timestamptz, which the PostgreSQL driver maps both ways
                .param("alertBefore", alertBefore.atOffset(ZoneOffset.UTC))
                .query((row, number) -> {
                    OffsetDateTime oldest = row.getObject("oldest", OffsetDateTime.class);
                    return new Pending(
                            row.getLong("total"), row.getLong("overdue"), oldest == null ? null : oldest.toInstant());
                })
                .single();
        incomplete.set(pending.total());
        if (pending.overdue() > 0) {
            log.atWarn()
                    .addKeyValue("event.action", "event_publications.overdue")
                    .addKeyValue("event_publications.overdue", pending.overdue())
                    .log(
                            "{} event publications pending for more than {}, the oldest since {}",
                            pending.overdue(),
                            properties.incompleteAlertAfter(),
                            pending.oldest());
        }
        return pending;
    }

    /**
     * @param total publications pending
     * @param overdue those pending for longer than {@code opswatch.events.incomplete-alert-after}
     * @param oldest when the oldest one was published, null if none is pending
     */
    public record Pending(
            long total, long overdue, @Nullable Instant oldest) {}
}
