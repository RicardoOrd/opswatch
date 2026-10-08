package io.github.ricardoord.opswatch.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.incident.IncidentOpened;
import io.github.ricardoord.opswatch.notification.NotificationRows;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acceptance criterion of OW-036: if the application stops between the commit that opens an incident and the creation
 * of its deliveries, they are created on the next start, once. The publication stays pending in the registry.
 *
 * <p>Two Spring contexts, one after the other, as in {@code ProjectCleanupRestartIT}. The first leaves the database as
 * a crash right after the commit would: the incident open, its publication written exactly as Spring Modulith 2.1.1
 * writes one ({@code PUBLISHED}, no completion), and no listener run. Then it is closed. The second one starts and must
 * create the deliveries.
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class DeliveryRestartIT {

    private static final Duration LISTENER = Duration.ofSeconds(15);

    /** The incident whose deliveries were cut short, and its channel, shared by both contexts. */
    private static final AtomicReference<Interrupted> INTERRUPTED = new AtomicReference<>();

    @Nested
    @Order(1)
    @IntegrationTest
    @DirtiesContext
    class BeforeTheRestart {

        @Autowired
        private ApplicationEventPublisher publisher;

        @Autowired
        private EventSerializer serializer;

        @Autowired
        private TransactionTemplate transactions;

        @Autowired
        private JdbcTemplate jdbc;

        @Test
        void anIncidentOpensAndItsDeliveriesAreNeverCreated() {
            NotificationRows rows = new NotificationRows(jdbc);
            UUID organization = rows.organization();
            UUID project = rows.project(organization);
            UUID channel = rows.channel(organization, null, ChannelType.EMAIL, true);
            String listenerId = listenerOfIncidentOpened(rows, organization, project);
            UUID incident = rows.openIncident(organization, project, "Payments API", now());
            IncidentOpened opened = opened(incident, organization, project);

            transactions.executeWithoutResult(tx -> jdbc.update(
                    """
                    INSERT INTO event_publication (id, event_type, listener_id, publication_date, serialized_event,
                        status, completion_attempts)
                    VALUES (?, ?, ?, ?, ?, 'PUBLISHED', 0)""",
                    UUID.randomUUID(),
                    IncidentOpened.class.getName(),
                    listenerId,
                    Timestamp.from(opened.openedAt()),
                    serializer.serialize(opened)));

            assertThat(rows.deliveriesOfIncident(incident)).isEmpty();
            INTERRUPTED.set(new Interrupted(incident, channel));
        }

        /** As the registry names the real listener: taken from a run that did happen, never written by hand. */
        private String listenerOfIncidentOpened(NotificationRows rows, UUID organization, UUID project) {
            UUID incident = rows.openIncident(organization, project, "Donations API", now());
            transactions.executeWithoutResult(tx -> publisher.publishEvent(opened(incident, organization, project)));
            AtomicReference<String> listenerId = new AtomicReference<>();
            await().atMost(LISTENER).untilAsserted(() -> {
                List<String> found = jdbc.queryForList(
                        "SELECT listener_id FROM event_publication_archive WHERE serialized_event LIKE ?",
                        String.class,
                        "%" + incident + "%");
                assertThat(found).singleElement().asString().contains("IncidentEventsListener");
                listenerId.set(found.getFirst());
            });
            return listenerId.get();
        }
    }

    @Nested
    @Order(2)
    @IntegrationTest
    class AfterTheRestart {

        @Autowired
        private JdbcTemplate jdbc;

        @Test
        void theNextStartCreatesTheDeliveriesOnce() {
            Interrupted interrupted = INTERRUPTED.get();
            assertThat(interrupted).as("the first context ran").isNotNull();
            NotificationRows rows = new NotificationRows(jdbc);

            await().atMost(LISTENER)
                    .untilAsserted(() -> assertThat(publicationsOf(interrupted.incident(), "event_publication_archive"))
                            .isOne());

            assertThat(publicationsOf(interrupted.incident(), "event_publication"))
                    .isZero();
            List<Map<String, Object>> deliveries = rows.deliveriesOfIncident(interrupted.incident());
            assertThat(deliveries)
                    .singleElement()
                    .satisfies(delivery -> assertThat(delivery)
                            .containsEntry("channel_id", interrupted.channel())
                            .containsEntry("event_type", "INCIDENT_OPENED")
                            .containsEntry("status", "PENDING"));
        }

        /** The database outlives the test run when Testcontainers reuses it: no test row is left behind. */
        @AfterEach
        void deleteTheTestPublications() {
            Interrupted interrupted = INTERRUPTED.get();
            if (interrupted != null) {
                for (String table : List.of("event_publication", "event_publication_archive")) {
                    jdbc.update(
                            "DELETE FROM " + table + " WHERE serialized_event LIKE ?",
                            "%" + interrupted.incident() + "%");
                }
            }
        }

        private long publicationsOf(UUID incident, String table) {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE serialized_event LIKE ?",
                    Long.class,
                    "%" + incident + "%");
            return count == null ? 0 : count;
        }
    }

    private static IncidentOpened opened(UUID incident, UUID organization, UUID project) {
        return new IncidentOpened(
                incident, organization, project, UUID.randomUUID(), "Payments API", now(), "TIMEOUT", null);
    }

    /** PostgreSQL keeps microseconds. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private record Interrupted(UUID incident, UUID channel) {}
}
