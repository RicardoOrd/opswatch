package io.github.ricardoord.opswatch.monitoring.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.organization.ProjectDeleted;
import io.github.ricardoord.opswatch.organization.application.ProjectService;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cleanup of a deleted project survives the application stopping between the commit of the deletion and its
 * listener (OW-044): the publication stays pending in the registry, and the next start runs it.
 *
 * <p>Two Spring contexts, one after the other, as in {@code EventPublicationRegistryIT}. The first leaves the database
 * as a crash right after the commit would: the project deleted, its publication written in the same transaction exactly
 * as Spring Modulith 2.1.1 writes one ({@code PUBLISHED}, no completion), and no listener run. Then it is closed. The
 * second one starts and must clean up.
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class ProjectCleanupRestartIT {

    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";
    private static final SettingsChanges DEFAULTS =
            new SettingsChanges(null, null, null, null, null, PatchField.absent(), null, null, null);
    private static final Duration CLEANUP = Duration.ofSeconds(15);

    /** The project whose cleanup was cut short, shared by both contexts. */
    private static final AtomicReference<UUID> INTERRUPTED = new AtomicReference<>();

    @Nested
    @Order(1)
    @IntegrationTest
    @DirtiesContext
    class BeforeTheRestart {

        @Autowired
        private MonitorService monitors;

        @Autowired
        private ProjectService projects;

        @Autowired
        private EventSerializer serializer;

        @Autowired
        private TransactionTemplate transactions;

        @Autowired
        private JdbcTemplate jdbc;

        @Test
        void aDeletionCommitsAndItsCleanupNeverRuns() {
            String listenerId = listenerOfProjectDeleted();
            UUID owner = newUser(jdbc);
            UUID organization = organizationOf(jdbc, owner);
            UUID project = projectOf(jdbc, organization);
            monitors.create(owner, project, "Payments API", HEALTH, DEFAULTS, List.of());
            monitors.create(owner, project, "Donations API", HEALTH, DEFAULTS, List.of());
            ProjectDeleted deletion = new ProjectDeleted(project, organization, Instant.now(), owner);

            transactions.executeWithoutResult(tx -> {
                jdbc.update("UPDATE projects SET deleted_at = now() WHERE id = ?", project);
                jdbc.update(
                        """
                        INSERT INTO event_publication (id, event_type, listener_id, publication_date, serialized_event,
                            status, completion_attempts)
                        VALUES (?, ?, ?, ?, ?, 'PUBLISHED', 0)""",
                        UUID.randomUUID(),
                        ProjectDeleted.class.getName(),
                        listenerId,
                        Timestamp.from(deletion.occurredAt()),
                        serializer.serialize(deletion));
            });

            assertThat(aliveMonitorsOf(jdbc, project)).isEqualTo(2);
            INTERRUPTED.set(project);
        }

        /** As the registry names the real listener: taken from a cleanup that did run, never written by hand. */
        private String listenerOfProjectDeleted() {
            UUID owner = newUser(jdbc);
            UUID project = projectOf(jdbc, organizationOf(jdbc, owner));
            projects.delete(owner, project);
            AtomicReference<String> listenerId = new AtomicReference<>();
            await().atMost(CLEANUP).untilAsserted(() -> {
                // notification has a listener of its own (OW-035): only the one of monitoring
                List<String> found = jdbc.queryForList(
                        """
                        SELECT listener_id FROM event_publication_archive
                        WHERE serialized_event LIKE ? AND listener_id LIKE ?""", String.class, "%" + project + "%", "%monitoring.application.ProjectDeletedListener%");
                assertThat(found).singleElement();
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
        void theNextStartDeletesTheMonitorsAndArchivesThePublication() {
            UUID project = INTERRUPTED.get();
            assertThat(project).as("the first context ran").isNotNull();

            await().atMost(CLEANUP)
                    .untilAsserted(
                            () -> assertThat(aliveMonitorsOf(jdbc, project)).isZero());

            List<Map<String, Object>> states = jdbc.queryForList("""
                    SELECT s.status, s.next_check_at FROM monitor_state s JOIN monitors m ON m.id = s.monitor_id
                    WHERE m.project_id = ?""", project);
            assertThat(states).hasSize(2).allSatisfy(state -> {
                assertThat(state).containsEntry("status", "PAUSED");
                assertThat(state.get("next_check_at")).isNull();
            });
            await().atMost(CLEANUP)
                    .untilAsserted(() -> assertThat(publicationsOf(project, "event_publication"))
                            .isZero());
            assertThat(publicationsOf(project, "event_publication_archive")).isOne();
        }

        /** The database outlives the test run when Testcontainers reuses it: no test row is left behind. */
        @AfterEach
        void deleteTheTestPublications() {
            UUID project = INTERRUPTED.get();
            if (project != null) {
                for (String table : List.of("event_publication", "event_publication_archive")) {
                    jdbc.update("DELETE FROM " + table + " WHERE serialized_event LIKE ?", "%" + project + "%");
                }
            }
        }

        private long publicationsOf(UUID project, String table) {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE serialized_event LIKE ?",
                    Long.class,
                    "%" + project + "%");
            return count == null ? 0 : count;
        }
    }

    private static long aliveMonitorsOf(JdbcTemplate jdbc, UUID project) {
        Long alive = jdbc.queryForObject(
                "SELECT count(*) FROM monitors WHERE project_id = ? AND deleted_at IS NULL", Long.class, project);
        return alive == null ? 0 : alive;
    }

    private static UUID projectOf(JdbcTemplate jdbc, UUID organization) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, 'Production', now(), now())""", id, organization);
        return id;
    }

    private static UUID organizationOf(JdbcTemplate jdbc, UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                id);
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, 'OWNER', now(), now())""", id, owner);
        return id;
    }

    private static UUID newUser(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", id, uniqueEmail());
        return id;
    }
}
