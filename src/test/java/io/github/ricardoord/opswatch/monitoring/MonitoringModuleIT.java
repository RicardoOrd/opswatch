package io.github.ricardoord.opswatch.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.PostgresTestcontainer;
import io.github.ricardoord.opswatch.TestEncryptionKeys;
import io.github.ricardoord.opswatch.egress.EgressHttpClients;
import io.github.ricardoord.opswatch.egress.TargetPolicy;
import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.ProjectDeleted;
import io.github.ricardoord.opswatch.organization.ProjectDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The monitoring module alone, as Spring Modulith bootstraps it, with the open module {@code shared}: what it does with
 * the events of other modules. The other modules are absent, and the parts of their public API it needs are mocks.
 * Classes of {@code shared} cannot be imported one by one: Spring Modulith filters out whatever falls outside the
 * modules it includes.
 */
@ApplicationModuleTest(extraIncludes = "shared")
@Import({PostgresTestcontainer.class, TestEncryptionKeys.class})
@ActiveProfiles("test")
class MonitoringModuleIT {

    private static final Duration CLEANUP = Duration.ofSeconds(10);

    @MockitoBean
    private AccessControl access;

    @MockitoBean
    private ProjectDirectory projects;

    @MockitoBean
    private TargetPolicy targets;

    /** A mock client from {@code create}, so that the client of the checks starts and closes. */
    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    private EgressHttpClients clients;

    @Autowired
    private JdbcTemplate jdbc;

    /** {@code ProjectDeleted} → every monitor of the project deleted, and one {@code MonitorDeleted} each. */
    @Test
    void deletesTheMonitorsOfADeletedProjectAndPublishesEachDeletion(
            Scenario scenario, AssertablePublishedEvents events) {
        UUID organization = insertOrganization();
        UUID project = insertProject(organization);
        UUID other = insertProject(organization);
        List<UUID> monitors = List.of(
                insertMonitor(organization, project, "Payments API"),
                insertMonitor(organization, project, "Donations API"),
                insertMonitor(organization, project, "Notifications API"));
        UUID elsewhere = insertMonitor(organization, other, "Payments API");
        UUID deletedBy = UUID.randomUUID();

        scenario.publish(new ProjectDeleted(project, organization, Instant.now(), deletedBy))
                .andWaitAtMost(CLEANUP)
                .andWaitForStateChange(() -> aliveMonitorsOf(project), alive -> alive == 0)
                .andVerify(alive -> assertThat(alive).isZero());

        assertThat(events.ofType(MonitorDeleted.class)
                        .matching(event -> event.projectId().equals(project)))
                .extracting(MonitorDeleted::monitorId)
                .containsExactlyInAnyOrderElementsOf(monitors);
        assertThat(events.ofType(MonitorDeleted.class)
                        .matching(event -> event.projectId().equals(project)))
                .allSatisfy(event -> {
                    assertThat(event.deletedBy()).isEqualTo(deletedBy);
                    assertThat(event.organizationId()).isEqualTo(organization);
                });
        assertThat(aliveMonitorsOf(other)).isOne();
        assertThat(events.ofType(MonitorDeleted.class)
                        .matching(event -> event.monitorId().equals(elsewhere)))
                .isEmpty();
    }

    /** Delivery is at least once: the same event again finds nothing to delete and publishes nothing. */
    @Test
    void aRepeatedProjectDeletedDoesNothingMore(Scenario scenario, AssertablePublishedEvents events) {
        UUID organization = insertOrganization();
        UUID project = insertProject(organization);
        insertMonitor(organization, project, "Payments API");
        ProjectDeleted deletion = new ProjectDeleted(project, organization, Instant.now(), UUID.randomUUID());
        scenario.publish(deletion)
                .andWaitAtMost(CLEANUP)
                .andWaitForStateChange(() -> aliveMonitorsOf(project), alive -> alive == 0)
                .andVerify(alive -> assertThat(alive).isZero());
        Long version =
                jdbc.queryForObject("SELECT max(version) FROM monitors WHERE project_id = ?", Long.class, project);

        scenario.publish(deletion)
                .andWaitAtMost(CLEANUP)
                .andWaitForStateChange(() -> completedCleanupsOf(project), completed -> completed == 2)
                .andVerify(completed -> assertThat(completed).isEqualTo(2));

        assertThat(events.ofType(MonitorDeleted.class)
                        .matching(event -> event.projectId().equals(project)))
                .hasSize(1);
        assertThat(jdbc.queryForObject("SELECT max(version) FROM monitors WHERE project_id = ?", Long.class, project))
                .isEqualTo(version);
    }

    private long aliveMonitorsOf(UUID project) {
        Long alive = jdbc.queryForObject(
                "SELECT count(*) FROM monitors WHERE project_id = ? AND deleted_at IS NULL", Long.class, project);
        return alive == null ? 0 : alive;
    }

    /** The cleanups of the project that the registry archived as done. */
    private long completedCleanupsOf(UUID project) {
        Long completed = jdbc.queryForObject(
                "SELECT count(*) FROM event_publication_archive WHERE serialized_event LIKE ?",
                Long.class,
                "%" + project + "%");
        return completed == null ? 0 : completed;
    }

    private UUID insertOrganization() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                id);
        return id;
    }

    private UUID insertProject(UUID organization) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", id, organization, "Project " + id);
        return id;
    }

    private UUID insertMonitor(UUID organization, UUID project, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO monitors (id, organization_id, project_id, name, url, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'https://api.example.com/health', now(), now())""", id, organization, project, name);
        jdbc.update("""
                INSERT INTO monitor_state (monitor_id, status_changed_at, next_check_at, updated_at)
                VALUES (?, now(), now(), now())""", id);
        return id;
    }
}
