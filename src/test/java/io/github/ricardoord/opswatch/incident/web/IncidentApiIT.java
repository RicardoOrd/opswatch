package io.github.ricardoord.opswatch.incident.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.identity.security.AccessTokenIssuer;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.application.CheckResultRecorder;
import io.github.ricardoord.opswatch.monitoring.application.MonitorService;
import io.github.ricardoord.opswatch.monitoring.application.SettingsChanges;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSnapshot;
import io.github.ricardoord.opswatch.organization.application.ProjectService;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code /api/v1/organizations/{orgId}/incidents}, {@code /api/v1/incidents/{incidentId}} and its {@code /acknowledge},
 * through the real security chain. Incidents come from real checks, recorded with the {@code CheckResultRecorder}.
 */
@IntegrationTest
class IncidentApiIT {

    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";
    /** A failure threshold of 3 and a recovery threshold of 2. */
    private static final SettingsChanges DEFAULTS =
            new SettingsChanges(null, null, null, null, null, PatchField.absent(), null, null, null);

    private static final CheckOutcome TIMED_OUT =
            CheckOutcome.down(FailureReason.TIMEOUT, null, null, "no response within the timeout");
    private static final CheckOutcome HEALTHY = CheckOutcome.up(200, Duration.ofMillis(143));

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccessTokenIssuer tokens;

    @Autowired
    private CheckResultRecorder recorder;

    @Autowired
    private MonitorService monitors;

    @Autowired
    private ProjectService projects;

    @Autowired
    private Clock clock;

    @Test
    void acknowledgesAnOpenIncidentWithANote() {
        Organization charityLink = newOrganization();
        String incident = takeDown(charityLink.newMonitor("Payments API"));

        MvcTestResult result = acknowledge(charityLink.owner(), incident, "{\"note\": \"  Investigando  \"}");

        assertThat(result).hasStatus(200).bodyJson().isLenientlyEqualTo("""
                {"id": "%s", "status": "ACKNOWLEDGED", "monitorName": "Payments API", "cause": "TIMEOUT",
                 "causeHttpStatus": null, "resolvedAt": null, "resolution": null, "durationSeconds": null,
                 "acknowledgedBy": {"id": "%s", "displayName": "Ana"},
                 "timeline": [
                   {"type": "OPENED", "actor": null, "note": null},
                   {"type": "ACKNOWLEDGED", "actor": {"id": "%s", "displayName": "Ana"}, "note": "Investigando"}],
                 "version": 1}""".formatted(
                        incident, charityLink.owner().id(), charityLink.owner().id()));
        assertThat(get(charityLink.owner(), incident))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void theBodyOfAnAcknowledgementIsOptional() {
        Organization charityLink = newOrganization();
        String incident = takeDown(charityLink.newMonitor("Payments API"));

        MvcTestResult result = as(charityLink.owner(), mvc.post().uri("/api/v1/incidents/" + incident + "/acknowledge"))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(JsonPath.<String>read(content(result), "$.timeline[1].note")).isNull();
    }

    /** Rule R7: only an open incident; a second acknowledgement, or one after the resolution, is a 409. */
    @Test
    void onlyAnOpenIncidentCanBeAcknowledged() {
        Organization charityLink = newOrganization();
        MonitorSnapshot payments = charityLink.newMonitor("Payments API");
        String acknowledged = takeDown(payments);
        assertThat(acknowledge(charityLink.owner(), acknowledged, "{}")).hasStatus(200);
        MonitorSnapshot donations = charityLink.newMonitor("Donations API");
        Instant start = clock.instant();
        String resolved = takeDown(donations, start);
        recover(donations, start.plusSeconds(3));

        for (String incident : List.of(acknowledged, resolved)) {
            assertThat(acknowledge(charityLink.owner(), incident, "{}"))
                    .as(incident)
                    .hasStatus(409)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("business-rule-violation");
        }
    }

    @Test
    void rejectsANoteItCannotKeep() {
        Organization charityLink = newOrganization();
        String incident = takeDown(charityLink.newMonitor("Payments API"));

        for (String body : List.of(
                "{\"note\": \"%s\"}".formatted("a".repeat(501)),
                "{\"note\": \"Line one\\nline two\"}",
                "{\"note\": \"Bell \\u0007\"}",
                "{\"note\": \"Hi\", \"status\": \"RESOLVED\"}")) {
            assertThat(acknowledge(charityLink.owner(), incident, body))
                    .as(body)
                    .hasStatus(400);
        }
        assertThat(acknowledge(charityLink.owner(), incident, "{\"note\": \"%s\"}".formatted("a".repeat(500))))
                .hasStatus(200);
    }

    /** A pause resolves it: who did it shows in the timeline, and the duration counts from the opening. */
    @Test
    void showsAResolvedIncidentWithItsDurationAndWhoResolvedIt() {
        Organization charityLink = newOrganization();
        MonitorSnapshot monitor = charityLink.newMonitor("Payments API");
        String incident = takeDown(monitor);

        monitors.pause(UUID.fromString(charityLink.owner().id()), monitor.monitorId());

        MvcTestResult result = get(charityLink.owner(), incident);
        assertThat(result).hasStatus(200).bodyJson().isLenientlyEqualTo("""
                {"status": "RESOLVED", "resolution": "MONITOR_PAUSED", "acknowledgedBy": null,
                 "timeline": [{"type": "OPENED", "actor": null},
                              {"type": "RESOLVED", "actor": {"id": "%s", "displayName": "Ana"}}]}""".formatted(
                        charityLink.owner().id()));
        Instant openedAt = Instant.parse(JsonPath.read(content(result), "$.openedAt"));
        Instant resolvedAt = Instant.parse(JsonPath.read(content(result), "$.resolvedAt"));
        assertThat(JsonPath.<Integer>read(content(result), "$.durationSeconds").longValue())
                .isEqualTo(Duration.between(openedAt, resolvedAt).toSeconds());
    }

    @Test
    void listsTheIncidentsOfTheOrganizationNewestFirstAndFiltersThem() {
        Organization charityLink = newOrganization();
        MonitorSnapshot payments = charityLink.newMonitor("Payments API");
        Instant start = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String older = takeDown(payments, start);
        recover(payments, start.plusSeconds(3));
        MonitorSnapshot donations = charityLink.newMonitor("Donations API");
        String newer = takeDown(donations, start.plusSeconds(600));
        String organization = charityLink.id().toString();

        assertThat(ids(list(charityLink.owner(), organization, Map.of()))).containsExactly(newer, older);
        assertThat(ids(list(charityLink.owner(), organization, Map.of("sort", "openedAt,asc"))))
                .containsExactly(older, newer);
        assertThat(ids(list(charityLink.owner(), organization, Map.of("status", "OPEN,ACKNOWLEDGED"))))
                .containsExactly(newer);
        assertThat(ids(list(charityLink.owner(), organization, Map.of("status", "RESOLVED"))))
                .containsExactly(older);
        assertThat(ids(list(
                        charityLink.owner(),
                        organization,
                        Map.of("monitorId", payments.monitorId().toString()))))
                .containsExactly(older);
        assertThat(ids(list(
                        charityLink.owner(),
                        organization,
                        Map.of("projectId", donations.projectId().toString()))))
                .containsExactly(newer);
        // from inclusive, to exclusive, on the opening (the third failure)
        assertThat(ids(list(
                        charityLink.owner(),
                        organization,
                        Map.of(
                                "from",
                                start.plusSeconds(3).toString(),
                                "to",
                                start.plusSeconds(603).toString()))))
                .containsExactly(older);
        assertThat(list(charityLink.owner(), organization, Map.of())).bodyJson().isLenientlyEqualTo("""
                {"items": [{"id": "%s", "status": "OPEN", "resolvedAt": null, "durationSeconds": null},
                           {"id": "%s", "organizationId": "%s", "projectId": "%s", "monitorId": "%s",
                            "monitorName": "Payments API", "status": "RESOLVED", "cause": "TIMEOUT",
                            "causeHttpStatus": null, "openedAt": "%s", "resolution": "AUTO_RECOVERED"}],
                 "page": {"totalElements": 2}}""".formatted(
                        newer, older, organization, payments.projectId(), payments.monitorId(), start.plusSeconds(3)));
    }

    @Test
    void rejectsParametersItDoesNotAllow() {
        Organization charityLink = newOrganization();
        String organization = charityLink.id().toString();
        String now = clock.instant().toString();

        for (Map<String, String> query : List.of(
                Map.of("status", "CLOSED"),
                Map.of("status", "open"),
                Map.of("projectId", "not-a-uuid"),
                Map.of("from", "yesterday"),
                Map.of("from", now, "to", now),
                Map.of("sort", "monitorName"),
                Map.of("size", "101"))) {
            assertThat(list(charityLink.owner(), organization, query))
                    .as("%s", query)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("invalid-parameter");
        }
    }

    /** History: the incidents of a deleted project stay in the listing and by id. */
    @Test
    void theIncidentsOfADeletedProjectStayVisible() {
        Organization charityLink = newOrganization();
        MonitorSnapshot monitor = charityLink.newMonitor("Payments API");
        String incident = takeDown(monitor);

        projects.delete(UUID.fromString(charityLink.owner().id()), monitor.projectId());

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(get(charityLink.owner(), incident))
                        .hasStatus(200)
                        .bodyJson()
                        .extractingPath("$.resolution")
                        .isEqualTo("MONITOR_DELETED"));
        assertThat(ids(list(charityLink.owner(), charityLink.id().toString(), Map.of())))
                .containsExactly(incident);
    }

    /** Someone from another organization gets exactly what a missing incident gets. */
    @Test
    void someoneFromAnotherOrganizationGets404AsForAMissingIncident() {
        Organization charityLink = newOrganization();
        String incident = takeDown(charityLink.newMonitor("Payments API"));
        Organization other = newOrganization();
        String missing = UUID.randomUUID().toString();

        for (String target : List.of(incident, missing)) {
            for (MvcTestResult attempt :
                    List.of(get(other.owner(), target), acknowledge(other.owner(), target, "{}"))) {
                assertThat(attempt)
                        .hasStatus(404)
                        .bodyJson()
                        .extractingPath("$.detail")
                        .isEqualTo("incident " + target + " was not found");
            }
        }
        assertThat(list(other.owner(), charityLink.id().toString(), Map.of())).hasStatus(404);
        assertThat(get(charityLink.owner(), incident))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("OPEN");
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/organizations/{orgId}/incidents'].get.parameters[*].name")
                .asArray()
                .containsExactlyInAnyOrder(
                        "orgId", "status", "projectId", "monitorId", "from", "to", "page", "size", "sort");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/incidents/{incidentId}/acknowledge'].post.responses")
                .asMap()
                .containsKeys("200", "400", "401", "403", "404", "409");
    }

    /**
     * Three failed checks from now: the monitor goes down and its incident opens. Never before the monitor existed: a
     * check that started before the last change of its status changes nothing (OW-027).
     *
     * @return the id of the incident
     */
    private String takeDown(MonitorSnapshot monitor) {
        return takeDown(monitor, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    private String takeDown(MonitorSnapshot monitor, Instant from) {
        for (int failure = 1; failure <= 3; failure++) {
            recorder.record(monitor, from.plusSeconds(failure), TIMED_OUT);
        }
        return jdbc.queryForObject(
                        "SELECT id FROM incidents WHERE monitor_id = ? AND status <> 'RESOLVED'",
                        UUID.class,
                        monitor.monitorId())
                .toString();
    }

    /** Two healthy checks after {@code downAt}, when the monitor went down. */
    private void recover(MonitorSnapshot monitor, Instant downAt) {
        recorder.record(monitor, downAt.plusSeconds(1), HEALTHY);
        recorder.record(monitor, downAt.plusSeconds(2), HEALTHY);
    }

    private static List<String> ids(MvcTestResult result) {
        assertThat(result).hasStatus(200);
        return JsonPath.read(content(result), "$.items[*].id");
    }

    private MvcTestResult list(Caller caller, String organization, Map<String, String> query) {
        MockMvcRequestBuilder request =
                as(caller, mvc.get().uri("/api/v1/organizations/" + organization + "/incidents"));
        query.forEach(request::param);
        return request.exchange();
    }

    private MvcTestResult get(Caller caller, String incident) {
        return as(caller, mvc.get().uri("/api/v1/incidents/" + incident)).exchange();
    }

    private MvcTestResult acknowledge(Caller caller, String incident, String body) {
        return as(
                        caller,
                        mvc.post()
                                .uri("/api/v1/incidents/" + incident + "/acknowledge")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .exchange();
    }

    private static MockMvcRequestBuilder as(Caller caller, MockMvcRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token());
    }

    private static String content(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A signed-in user: the id and an access token. */
    private record Caller(String id, String token) {}

    /** An organization with its {@code OWNER}, where each monitor gets a project of its own. */
    private record Organization(UUID id, Caller owner, IncidentApiIT test) {

        MonitorSnapshot newMonitor(String name) {
            UUID project = UUID.randomUUID();
            test.jdbc.update("""
                    INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                    VALUES (?, ?, ?, now(), now())""", project, id, "Project " + project);
            return MonitorSnapshot.of(test.monitors
                    .create(UUID.fromString(owner.id()), project, name, HEALTH, DEFAULTS, List.of())
                    .monitor());
        }
    }

    private Organization newOrganization() {
        UUID owner = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", owner, uniqueEmail());
        UUID organization = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                organization);
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, 'OWNER', now(), now())""", organization, owner);
        return new Organization(
                organization, new Caller(owner.toString(), tokens.issue(owner).value()), this);
    }
}
