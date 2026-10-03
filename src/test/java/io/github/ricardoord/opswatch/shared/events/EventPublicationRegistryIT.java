package io.github.ricardoord.opswatch.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ricardoord.opswatch.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Event Publication Registry against PostgreSQL across a restart (OW-034, docs/architecture/events.md): a
 * publication is stored in the publisher's transaction, survives the application stopping before its listener
 * finished, runs again on the next start and ends up in the archive.
 *
 * <p>Two Spring contexts, one after the other, with the same listener so that its id matches: the first is closed
 * after its tests, as a stopped application would be. The listener fails in the first one, which leaves the publication
 * pending exactly as a crash in the middle of it would; the registry resubmits both on restart.
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class EventPublicationRegistryIT {

    private static final UUID SURVIVES_THE_RESTART = UUID.randomUUID();
    private static final AtomicBoolean LISTENER_FAILS = new AtomicBoolean(true);
    private static final List<UUID> ATTEMPTS = new CopyOnWriteArrayList<>();
    private static final List<UUID> COMPLETED = new CopyOnWriteArrayList<>();
    private static final Duration ASYNC = Duration.ofSeconds(10);

    @Nested
    @Order(1)
    @IntegrationTest
    @Import(TestListener.class)
    @DirtiesContext
    class BeforeTheRestart {

        @Autowired
        private ApplicationEventPublisher events;

        @Autowired
        private TransactionTemplate transactions;

        @Autowired
        private JdbcClient jdbc;

        @Test
        void aPublicationWhoseListenerDidNotFinishStaysPending() {
            transactions.executeWithoutResult(tx -> events.publishEvent(new RegistryTestEvent(SURVIVES_THE_RESTART)));

            await().atMost(ASYNC)
                    .untilAsserted(() -> assertThat(pendingStatus(jdbc, SURVIVES_THE_RESTART))
                            .isEqualTo("FAILED"));
            assertThat(ATTEMPTS).containsExactly(SURVIVES_THE_RESTART);
            assertThat(COMPLETED).isEmpty();
            assertThat(archived(jdbc, SURVIVES_THE_RESTART)).isZero();

            // The next start of the application finds a listener that works
            LISTENER_FAILS.set(false);
        }

        @Test
        void aPublisherThatRollsBackLeavesNoPublication() {
            UUID rolledBack = UUID.randomUUID();

            transactions.executeWithoutResult(tx -> {
                events.publishEvent(new RegistryTestEvent(rolledBack));
                tx.setRollbackOnly();
            });

            assertThat(pending(jdbc, rolledBack)).isZero();
            assertThat(archived(jdbc, rolledBack)).isZero();
            assertThat(ATTEMPTS).doesNotContain(rolledBack);
        }
    }

    @Nested
    @Order(2)
    @IntegrationTest
    @Import(TestListener.class)
    class AfterTheRestart {

        @Autowired
        private JdbcClient jdbc;

        @Test
        void runsThePendingPublicationOnceAndArchivesIt() {
            await().atMost(ASYNC)
                    .untilAsserted(() ->
                            assertThat(archived(jdbc, SURVIVES_THE_RESTART)).isOne());

            assertThat(pending(jdbc, SURVIVES_THE_RESTART)).isZero();
            assertThat(COMPLETED).containsExactly(SURVIVES_THE_RESTART);
            Map<String, Object> archive = jdbc.sql("""
                            SELECT status, completion_date FROM event_publication_archive
                            WHERE serialized_event LIKE :id""")
                    .param("id", "%" + SURVIVES_THE_RESTART + "%")
                    .query()
                    .singleRow();
            assertThat(archive.get("status")).isEqualTo("COMPLETED");
            assertThat(archive.get("completion_date")).isNotNull();
        }

        /** The database outlives the test run when Testcontainers reuses it: no test row is left behind. */
        @AfterEach
        void deleteTheTestPublications() {
            for (String table : List.of("event_publication", "event_publication_archive")) {
                jdbc.sql("DELETE FROM " + table + " WHERE event_type = :type")
                        .param("type", RegistryTestEvent.class.getName())
                        .update();
            }
        }
    }

    private static @Nullable String pendingStatus(JdbcClient jdbc, UUID eventId) {
        return jdbc.sql("""
                        SELECT status FROM event_publication
                        WHERE serialized_event LIKE :id AND completion_date IS NULL""")
                .param("id", "%" + eventId + "%")
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static long pending(JdbcClient jdbc, UUID eventId) {
        return count(jdbc, "event_publication", eventId);
    }

    private static long archived(JdbcClient jdbc, UUID eventId) {
        return count(jdbc, "event_publication_archive", eventId);
    }

    private static long count(JdbcClient jdbc, String table, UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE serialized_event LIKE :id")
                .param("id", "%" + eventId + "%")
                .query(Long.class)
                .single();
    }

    public record RegistryTestEvent(UUID id) {}

    /**
     * Asynchronous, after the commit and with the registry, like every listener between modules. Imported as a plain
     * class: a nested configuration class would become the default configuration of the nested tests.
     */
    static class TestListener {

        @ApplicationModuleListener
        void on(RegistryTestEvent event) {
            ATTEMPTS.add(event.id());
            if (LISTENER_FAILS.get()) {
                throw new IllegalStateException("The listener stops before finishing, as in a crash");
            }
            COMPLETED.add(event.id());
        }
    }
}
