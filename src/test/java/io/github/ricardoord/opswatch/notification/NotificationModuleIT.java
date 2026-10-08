package io.github.ricardoord.opswatch.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.PostgresTestcontainer;
import io.github.ricardoord.opswatch.TestEncryptionKeys;
import io.github.ricardoord.opswatch.egress.TargetPolicy;
import io.github.ricardoord.opswatch.incident.IncidentOpened;
import io.github.ricardoord.opswatch.incident.IncidentResolved;
import io.github.ricardoord.opswatch.incident.Resolution;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.ProjectDirectory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The notification module alone, as Spring Modulith bootstraps it, with the open module {@code shared}: what it does
 * with the events of the incidents. {@code incident} is absent, so the incidents, their monitors and the channels are
 * rows inserted by hand, and each event is published inside a transaction, as {@code incident} does. The parts of the
 * API of {@code organization} and {@code egress} that the channels need are mocks.
 */
@ApplicationModuleTest(extraIncludes = "shared")
@Import({PostgresTestcontainer.class, TestEncryptionKeys.class})
@ActiveProfiles("test")
class NotificationModuleIT {

    @MockitoBean
    private AccessControl access;

    @MockitoBean
    private ProjectDirectory projects;

    @MockitoBean
    private TargetPolicy targets;

    @Autowired
    private JdbcTemplate jdbc;

    private NotificationRows rows;
    private UUID organization;
    private UUID project;
    private UUID incident;

    @BeforeEach
    void anOpenIncident() {
        rows = new NotificationRows(jdbc);
        organization = rows.organization();
        project = rows.project(organization);
        incident = rows.openIncident(organization, project, "Payments API", now());
    }

    /**
     * Each enabled channel of the organization that covers the project: those of every project and those of this one.
     * Not those of another project, a disabled one, nor those of another organization.
     */
    @Test
    void anOpenedIncidentGetsOnePendingDeliveryPerChannelThatCoversItsProject(Scenario scenario) {
        UUID everyProject = rows.channel(organization, null, ChannelType.EMAIL, true);
        UUID thisProject = rows.channel(organization, project, ChannelType.WEBHOOK, true);
        rows.channel(organization, rows.project(organization), ChannelType.EMAIL, true);
        rows.channel(organization, null, ChannelType.EMAIL, false);
        UUID otherOrganization = rows.organization();
        rows.channel(otherOrganization, null, ChannelType.EMAIL, true);

        List<Map<String, Object>> deliveries = publishAndWait(scenario, opened(), 2);

        assertThat(deliveries)
                .extracting(delivery -> delivery.get("channel_id"))
                .containsExactlyInAnyOrder(everyProject, thisProject);
        assertThat(deliveries).allSatisfy(delivery -> {
            assertThat(delivery)
                    .containsEntry("event_type", "INCIDENT_OPENED")
                    .containsEntry("status", "PENDING")
                    .containsEntry("attempts", 0)
                    .containsEntry("last_attempt_at", null)
                    .containsEntry("sent_at", null);
            // The first wait of the backoff is 0 s: due as soon as it is created
            assertThat(delivery.get("next_attempt_at")).isEqualTo(delivery.get("created_at"));
        });
    }

    /** At least once: the registry may deliver an event twice, and the channel hears of it once (OW-036). */
    @Test
    void aRepeatedEventAddsNoSecondDelivery(Scenario scenario) {
        rows.channel(organization, null, ChannelType.EMAIL, true);
        IncidentOpened opened = opened();
        publishAndWait(scenario, opened, 1);

        scenario.publish(opened)
                .andWaitForStateChange(() -> handledPublicationsOf(incident), handled -> handled == 2)
                .andVerify(handled ->
                        assertThat(rows.deliveriesOfIncident(incident)).hasSize(1));
    }

    @Test
    void aResolvedIncidentGetsDeliveriesOfItsOwn(Scenario scenario) {
        rows.channel(organization, null, ChannelType.EMAIL, true);
        publishAndWait(scenario, opened(), 1);
        Instant openedAt = now().minusSeconds(600);

        List<Map<String, Object>> deliveries = publishAndWait(
                scenario,
                new IncidentResolved(
                        incident,
                        organization,
                        project,
                        UUID.randomUUID(),
                        "Payments API",
                        openedAt,
                        now(),
                        Resolution.AUTO_RECOVERED),
                2);

        assertThat(deliveries)
                .extracting(delivery -> delivery.get("event_type"))
                .containsExactly("INCIDENT_OPENED", "INCIDENT_RESOLVED");
    }

    @Test
    void anOrganizationWithoutChannelsGetsNoDelivery(Scenario scenario) {
        scenario.publish(opened())
                .andWaitForStateChange(() -> handledPublicationsOf(incident), handled -> handled == 1)
                .andVerify(handled ->
                        assertThat(rows.deliveriesOfIncident(incident)).isEmpty());
    }

    private List<Map<String, Object>> publishAndWait(Scenario scenario, Object event, int expected) {
        scenario.publish(event)
                .andWaitForStateChange(() -> rows.deliveriesOfIncident(incident), found -> found.size() == expected)
                .andVerify(found -> assertThat(found).hasSize(expected));
        return rows.deliveriesOfIncident(incident);
    }

    private IncidentOpened opened() {
        return new IncidentOpened(
                incident, organization, project, UUID.randomUUID(), "Payments API", now(), "UNEXPECTED_STATUS", 503);
    }

    /** The publications of the incident that the listener completed: they move to the archive. */
    private long handledPublicationsOf(UUID incident) {
        Long handled = jdbc.queryForObject("""
                SELECT count(*) FROM event_publication_archive
                WHERE serialized_event LIKE ? AND listener_id LIKE '%IncidentEventsListener%'""", Long.class, "%" + incident + "%");
        return handled == null ? 0 : handled;
    }

    /** PostgreSQL keeps microseconds. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
