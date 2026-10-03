package io.github.ricardoord.opswatch.monitoring.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.monitoring.MonitorDeleted;
import io.github.ricardoord.opswatch.monitoring.MonitorPaused;
import io.github.ricardoord.opswatch.organization.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code pause}, {@code resume} and {@code DELETE} of a monitor, and its deletion with its project (OW-044). */
@IntegrationTest
@RecordApplicationEvents
class MonitorLifecycleApiIT {

    private static final String PASSWORD = "correct horse battery";
    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";
    private static final Duration CLEANUP = Duration.ofSeconds(10);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEvents events;

    @Test
    void aPauseUnschedulesItUntilItIsResumed() {
        Caller owner = signedIn();
        String organization = organizationOf(owner);
        String project = projectOf(owner, organization, "Production");
        String id = monitorOf(owner, project, "Payments API");
        Caller member = memberOf(organization, Role.MEMBER);
        // As the engine would leave it after a few failed checks
        jdbc.update(
                "UPDATE monitor_state SET status = 'DOWN', consecutive_failures = 3 WHERE monitor_id = ?",
                UUID.fromString(id));

        MvcTestResult paused = post(member, id, "pause");

        assertThat(paused).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(paused).bodyJson().extractingPath("$.state.status").isEqualTo("PAUSED");
        assertThat(paused).bodyJson().extractingPath("$.state.nextCheckAt").isNull();
        assertThat(paused)
                .bodyJson()
                .extractingPath("$.state.consecutiveFailures")
                .isEqualTo(0);
        assertThat(state(id)).containsEntry("status", "PAUSED").containsEntry("next_check_at", null);
        assertThat(events.stream(MonitorPaused.class)).singleElement().satisfies(event -> {
            assertThat(event.monitorId()).hasToString(id);
            assertThat(event.projectId()).hasToString(project);
            assertThat(event.organizationId()).hasToString(organization);
            assertThat(event.pausedBy()).hasToString(member.id());
        });
        assertThat(post(member, id, "pause"))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("The monitor is already paused.");

        Instant beforeResuming = Instant.now();
        MvcTestResult resumed = post(member, id, "resume");

        assertThat(resumed)
                .hasStatus(200)
                .bodyJson()
                .extractingPath("$.state.status")
                .isEqualTo("PENDING");
        assertThat(Instant.parse(read(resumed, "$.state.nextCheckAt")))
                .isBetween(beforeResuming.minusSeconds(1), Instant.now().plusSeconds(30));
        assertThat(post(member, id, "resume"))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("The monitor is not paused.");
    }

    @Test
    void aViewerCannotAndSomeoneFromAnotherOrganizationGets404() {
        Caller owner = signedIn();
        String organization = organizationOf(owner);
        String id = monitorOf(owner, projectOf(owner, organization, "Production"), "Payments API");
        Caller viewer = memberOf(organization, Role.VIEWER);
        Caller mallory = signedIn();
        String missing = UUID.randomUUID().toString();

        for (String action : List.of("pause", "resume")) {
            assertThat(post(viewer, id, action)).hasStatus(403);
            for (String target : List.of(id, missing)) {
                assertThat(post(mallory, target, action))
                        .hasStatus(404)
                        .bodyJson()
                        .extractingPath("$.detail")
                        .isEqualTo("monitor " + target + " was not found");
            }
        }
        assertThat(delete(viewer, id)).hasStatus(403);
        assertThat(delete(mallory, id)).hasStatus(404);
        assertThat(delete(mallory, missing)).hasStatus(404);

        assertThat(get(owner, id))
                .hasStatus(200)
                .bodyJson()
                .extractingPath("$.state.status")
                .isEqualTo("PENDING");
    }

    @Test
    void aDeletedMonitorIsGoneAndItsDeletionIsPublished() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String project = projectOf(ana, organization, "Production");
        String id = monitorOf(ana, project, "Payments API");

        assertThat(delete(ana, id)).hasStatus(204);

        assertThat(get(ana, id)).hasStatus(404);
        assertThat(delete(ana, id)).hasStatus(404);
        assertThat(post(ana, id, "resume")).hasStatus(404);
        assertThat(patch(ana, id, "{\"name\": \"Revived\"}")).hasStatus(404);
        assertThat(list(ana, project))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(0);
        assertThat(summary(ana, project)).bodyJson().extractingPath("$.total").isEqualTo(0);
        // Logical, unscheduled, and a new version that any stale PATCH fails against
        assertThat(jdbc.queryForMap(
                        "SELECT deleted_at IS NOT NULL AS deleted, version FROM monitors WHERE id = ?",
                        UUID.fromString(id)))
                .containsEntry("deleted", true)
                .containsEntry("version", 1L);
        assertThat(state(id)).containsEntry("status", "PAUSED").containsEntry("next_check_at", null);
        assertThat(events.stream(MonitorDeleted.class)).singleElement().satisfies(event -> {
            assertThat(event.monitorId()).hasToString(id);
            assertThat(event.deletedBy()).hasToString(ana.id());
        });
    }

    @Test
    void aDeletedMonitorFreesItsNameAndItsPlaceInTheQuota() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");
        String id = monitorOf(ana, project, "Payments API");

        assertThat(delete(ana, id)).hasStatus(204);

        assertThat(create(ana, project, "Payments API")).hasStatus(201);
    }

    /** After the commit of the deletion, asynchronously: the monitors end deleted and unscheduled, paused ones too. */
    @Test
    void deletingAProjectDeletesItsMonitors() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String production = projectOf(ana, organization, "Production");
        String staging = projectOf(ana, organization, "Staging");
        List<String> ids = List.of(
                monitorOf(ana, production, "Payments API"),
                monitorOf(ana, production, "Donations API"),
                monitorOf(ana, production, "Notifications API"));
        assertThat(post(ana, ids.getLast(), "pause")).hasStatus(200);
        String elsewhere = monitorOf(ana, staging, "Payments API");

        assertThat(as(ana, mvc.delete().uri("/api/v1/projects/" + production)).exchange())
                .hasStatus(204);

        await().atMost(CLEANUP)
                .untilAsserted(() -> assertThat(aliveMonitorsOf(production)).isZero());
        for (String id : ids) {
            assertThat(state(id)).containsEntry("status", "PAUSED").containsEntry("next_check_at", null);
        }
        assertThat(get(ana, elsewhere)).hasStatus(200);
    }

    @Test
    void deletingAnOrganizationDeletesTheMonitorsOfEachProject() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String production = projectOf(ana, organization, "Production");
        String staging = projectOf(ana, organization, "Staging");
        monitorOf(ana, production, "Payments API");
        monitorOf(ana, staging, "Payments API");

        assertThat(as(ana, mvc.delete().uri("/api/v1/organizations/" + organization))
                        .exchange())
                .hasStatus(204);

        await().atMost(CLEANUP).untilAsserted(() -> {
            assertThat(aliveMonitorsOf(production)).isZero();
            assertThat(aliveMonitorsOf(staging)).isZero();
        });
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/monitors/{monitorId}/pause'].post.responses")
                .asMap()
                .containsKeys("200", "401", "403", "404", "409");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/monitors/{monitorId}'].delete.responses")
                .asMap()
                .containsKeys("204", "401", "403", "404");
    }

    /** A signed-in user: the access token and the id. */
    private record Caller(String token, String id) {}

    private long aliveMonitorsOf(String projectId) {
        Long alive = jdbc.queryForObject(
                "SELECT count(*) FROM monitors WHERE project_id = ? AND deleted_at IS NULL",
                Long.class,
                UUID.fromString(projectId));
        return alive == null ? 0 : alive;
    }

    private Map<String, Object> state(String monitorId) {
        return jdbc.queryForMap(
                "SELECT status, next_check_at FROM monitor_state WHERE monitor_id = ?", UUID.fromString(monitorId));
    }

    private Caller signedIn() {
        String email = uniqueEmail();
        MvcTestResult registration = json(mvc.post(), "/api/v1/auth/register", """
                        {"email": "%s", "displayName": "Ana", "password": "%s"}""".formatted(email, PASSWORD))
                .exchange();
        assertThat(registration).hasStatus(201);
        MvcTestResult login = json(mvc.post(), "/api/v1/auth/login", """
                        {"email": "%s", "password": "%s"}""".formatted(email, PASSWORD))
                .exchange();
        assertThat(login).hasStatus(200);
        return new Caller(read(login, "$.accessToken"), read(registration, "$.id"));
    }

    private String organizationOf(Caller caller) {
        MvcTestResult result = as(caller, json(mvc.post(), "/api/v1/organizations", "{\"name\": \"CharityLink\"}"))
                .exchange();
        assertThat(result).hasStatus(201);
        return read(result, "$.id");
    }

    /** Straight into the table: the member endpoints are tested elsewhere. */
    private Caller memberOf(String organizationId, Role role) {
        Caller caller = signedIn();
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", UUID.fromString(organizationId), UUID.fromString(caller.id()), role.name());
        return caller;
    }

    private String projectOf(Caller caller, String organizationId, String name) {
        MvcTestResult result = as(
                        caller,
                        json(
                                mvc.post(),
                                "/api/v1/organizations/" + organizationId + "/projects",
                                "{\"name\": \"%s\"}".formatted(name)))
                .exchange();
        assertThat(result).hasStatus(201);
        return read(result, "$.id");
    }

    private String monitorOf(Caller caller, String projectId, String name) {
        MvcTestResult result = create(caller, projectId, name);
        assertThat(result).hasStatus(201);
        return read(result, "$.id");
    }

    private MvcTestResult create(Caller caller, String projectId, String name) {
        return as(
                        caller,
                        json(
                                mvc.post(),
                                "/api/v1/projects/" + projectId + "/monitors",
                                "{\"name\": \"%s\", \"url\": \"%s\"}".formatted(name, HEALTH)))
                .exchange();
    }

    private MvcTestResult post(Caller caller, String id, String action) {
        return as(caller, mvc.post().uri("/api/v1/monitors/" + id + "/" + action))
                .exchange();
    }

    private MvcTestResult delete(Caller caller, String id) {
        return as(caller, mvc.delete().uri("/api/v1/monitors/" + id)).exchange();
    }

    private MvcTestResult get(Caller caller, String id) {
        return as(caller, mvc.get().uri("/api/v1/monitors/" + id)).exchange();
    }

    private MvcTestResult patch(Caller caller, String id, String body) {
        return as(caller, json(mvc.patch(), "/api/v1/monitors/" + id, body)).exchange();
    }

    private MvcTestResult list(Caller caller, String projectId) {
        return as(caller, mvc.get().uri("/api/v1/projects/" + projectId + "/monitors"))
                .exchange();
    }

    private MvcTestResult summary(Caller caller, String projectId) {
        return as(caller, mvc.get().uri("/api/v1/projects/" + projectId + "/monitors/summary"))
                .exchange();
    }

    private static MockMvcRequestBuilder as(Caller caller, MockMvcRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token());
    }

    private static MockMvcRequestBuilder json(MockMvcRequestBuilder request, String uri, String body) {
        return request.uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String read(MvcTestResult result, String path) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), path);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
