package io.github.ricardoord.opswatch.monitoring.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.egress.internal.FakeHostResolver;
import io.github.ricardoord.opswatch.organization.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code /api/v1/projects/{projectId}/monitors} and {@code /api/v1/monitors}, through the real security chain. */
@IntegrationTest
class MonitorApiIT {

    private static final String PASSWORD = "correct horse battery";
    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FakeHostResolver resolver;

    @Test
    void aMemberCreatesAMonitorThatAViewerReads() {
        Caller owner = signedIn();
        String organization = organizationOf(owner);
        String project = projectOf(owner, organization, "Production");
        Caller member = memberOf(organization, Role.MEMBER);
        Caller viewer = memberOf(organization, Role.VIEWER);

        MvcTestResult created = create(member, project, """
                {"name": " Payments API ", "url": "%s", "intervalSeconds": 60}""".formatted(HEALTH));

        assertThat(created).hasStatus(201);
        String id = read(created, "$.id");
        assertThat(created).headers().hasValue(HttpHeaders.LOCATION, "/api/v1/monitors/" + id);
        assertThat(created).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(created).bodyJson().extractingPath("$.projectId").isEqualTo(project);
        assertThat(created).bodyJson().extractingPath("$.organizationId").isEqualTo(organization);
        assertThat(created).bodyJson().extractingPath("$.name").isEqualTo("Payments API");
        assertThat(created).bodyJson().extractingPath("$.url").isEqualTo(HEALTH);
        assertThat(created).bodyJson().extractingPath("$.httpMethod").isEqualTo("GET");
        assertThat(created).bodyJson().extractingPath("$.expectedStatus").isEqualTo(Map.of("min", 200, "max", 299));
        assertThat(created).bodyJson().extractingPath("$.intervalSeconds").isEqualTo(60);
        assertThat(created).bodyJson().extractingPath("$.timeoutMs").isEqualTo(10_000);
        assertThat(created).bodyJson().extractingPath("$.degradedThresholdMs").isNull();
        assertThat(created).bodyJson().extractingPath("$.followRedirects").isEqualTo(true);
        assertThat(created).bodyJson().extractingPath("$.failureThreshold").isEqualTo(3);
        assertThat(created).bodyJson().extractingPath("$.recoveryThreshold").isEqualTo(2);
        assertThat(created).bodyJson().extractingPath("$.state.status").isEqualTo("PENDING");
        assertThat(created).bodyJson().extractingPath("$.state.lastCheckedAt").isNull();
        assertThat(created)
                .bodyJson()
                .extractingPath("$.state.consecutiveFailures")
                .isEqualTo(0);
        assertThat(created).bodyJson().extractingPath("$.version").isEqualTo(0);
        // The initial jitter: the first check within 30 s, never a whole interval away
        Instant createdAt = Instant.parse(read(created, "$.createdAt"));
        assertThat(Instant.parse(read(created, "$.state.nextCheckAt"))).isBetween(createdAt, createdAt.plusSeconds(30));

        assertThat(get(viewer, id)).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(list(viewer, project, Map.of()))
                .bodyJson()
                .extractingPath("$.items[*].name")
                .isEqualTo(List.of("Payments API"));
    }

    @Test
    void theRoleDecidesWhatAMemberCanDo() {
        Caller owner = signedIn();
        String organization = organizationOf(owner);
        String project = projectOf(owner, organization, "Production");
        String id = monitorOf(owner, project, "Payments API");
        Caller admin = memberOf(organization, Role.ADMIN);
        Caller viewer = memberOf(organization, Role.VIEWER);

        assertThat(create(viewer, project, body("Donations API")))
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("access-denied");
        assertThat(patch(viewer, id, "{\"name\": \"Renamed\"}", null)).hasStatus(403);
        assertThat(get(viewer, id)).hasStatus(200);
        assertThat(list(viewer, project, Map.of())).hasStatus(200);
        assertThat(summary(viewer, project)).hasStatus(200);

        assertThat(patch(admin, id, "{\"name\": \"Renamed by an admin\"}", null))
                .hasStatus(200);
        assertThat(get(viewer, id)).bodyJson().extractingPath("$.name").isEqualTo("Renamed by an admin");
    }

    @Test
    void someoneFromAnotherOrganizationGets404AsForAMissingMonitorAndChangesNothing() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");
        String id = monitorOf(ana, project, "Payments API");
        Caller mallory = signedIn();
        organizationOf(mallory);
        String missing = UUID.randomUUID().toString();

        // Exactly what a missing monitor gets, with a detail that names neither its project nor its organization
        for (String target : List.of(id, missing)) {
            assertThat(get(mallory, target))
                    .hasStatus(404)
                    .bodyJson()
                    .extractingPath("$.detail")
                    .isEqualTo("monitor " + target + " was not found");
            assertThat(patch(mallory, target, "{\"name\": \"Stolen\"}", null))
                    .hasStatus(404)
                    .bodyJson()
                    .extractingPath("$.detail")
                    .isEqualTo("monitor " + target + " was not found");
        }
        for (MvcTestResult attempt : List.of(
                create(mallory, project, body("Planted")),
                list(mallory, project, Map.of()),
                summary(mallory, project))) {
            assertThat(attempt)
                    .hasStatus(404)
                    .bodyJson()
                    .extractingPath("$.detail")
                    .isEqualTo("project " + project + " was not found");
        }

        assertThat(get(ana, id))
                .hasStatus(200)
                .bodyJson()
                .extractingPath("$.name")
                .isEqualTo("Payments API");
        assertThat(list(ana, project, Map.of()))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(1);
    }

    /** Authorization comes before the DNS: only someone allowed can make the server resolve a name. */
    @Test
    void onlySomeoneAllowedMakesTheServerResolveTheUrl() {
        Caller owner = signedIn();
        String organization = organizationOf(owner);
        String project = projectOf(owner, organization, "Production");
        String id = monitorOf(owner, project, "Payments API");
        Caller viewer = memberOf(organization, Role.VIEWER);
        Caller mallory = signedIn();
        String host = "probe-" + UUID.randomUUID() + ".example.com";
        String probe = "{\"name\": \"Probe\", \"url\": \"https://%s/\"}".formatted(host);

        assertThat(create(viewer, project, probe)).hasStatus(403);
        assertThat(create(mallory, project, probe)).hasStatus(404);
        assertThat(patch(viewer, id, "{\"url\": \"https://%s/\"}".formatted(host), null))
                .hasStatus(403);
        assertThat(patch(mallory, id, "{\"url\": \"https://%s/\"}".formatted(host), null))
                .hasStatus(404);
        assertThat(resolver.lookups()).doesNotContain(host);

        assertThat(create(owner, project, probe)).hasStatus(201);
        assertThat(resolver.lookups()).contains(host);
    }

    @Test
    void theProjectAndTheOrganizationComeFromThePathNeverFromTheBody() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String project = projectOf(ana, organization, "Production");
        String another = projectOf(ana, organization, "Staging");
        String id = monitorOf(ana, project, "Payments API");

        for (String planted : List.of(
                "\"projectId\": \"%s\"".formatted(another),
                "\"organizationId\": \"%s\"".formatted(UUID.randomUUID()))) {
            assertThat(create(ana, project, """
                            {"name": "Planted", "url": "%s", %s}""".formatted(HEALTH, planted)))
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("malformed-request");
            assertThat(patch(ana, id, "{%s}".formatted(planted), null))
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("malformed-request");
        }
        assertThat(list(ana, another, Map.of()))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(0);
        assertThat(get(ana, id)).bodyJson().extractingPath("$.projectId").isEqualTo(project);
    }

    @Test
    void rejectsTheTargetsOfTheSsrfPolicyOnCreatingAndOnChanging() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");
        String id = monitorOf(ana, project, "Payments API");

        for (String url : List.of(
                "http://127.0.0.1/",
                "http://169.254.169.254/latest/meta-data/",
                "http://" + TestHostResolver.PRIVATE_HOST + "/",
                "http://localhost:8080/",
                "ftp://" + TestHostResolver.PUBLIC_HOST + "/")) {
            MvcTestResult created = create(ana, project, "{\"name\": \"Internal\", \"url\": \"%s\"}".formatted(url));
            assertThat(created)
                    .hasStatus(422)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("target-not-allowed");
            // The rule, never the address the host resolved to
            assertThat(created).bodyJson().extractingPath("$.detail").asString().doesNotContain("10.0.0.5");
            assertThat(patch(ana, id, "{\"url\": \"%s\"}".formatted(url), null))
                    .hasStatus(422)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("target-not-allowed");
        }
        assertThat(get(ana, id)).bodyJson().extractingPath("$.url").isEqualTo(HEALTH);
        assertThat(list(ana, project, Map.of()))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(1);
    }

    @Test
    void storesAndShowsTheUrlNormalized() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");

        MvcTestResult created = create(ana, project, """
                {"name": "Payments API", "url": "HTTPS://API.Example.COM/health?Full=1#top"}""");

        assertThat(created).hasStatus(201);
        assertThat(created)
                .bodyJson()
                .extractingPath("$.url")
                .isEqualTo("https://" + TestHostResolver.PUBLIC_HOST + "/health?Full=1");
    }

    /** The quota is per organization, across its projects; deleted monitors do not count. */
    @Test
    void theFiftyFirstMonitorOfAnOrganizationIsOverTheQuota() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String production = projectOf(ana, organization, "Production");
        String staging = projectOf(ana, organization, "Staging");
        for (int i = 1; i <= 49; i++) {
            insertMonitor(organization, production, "Existing " + i);
        }
        assertThat(create(ana, staging, body("Monitor 50"))).hasStatus(201);

        assertThat(create(ana, production, body("Monitor 51")))
                .hasStatus(422)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("quota-exceeded");

        jdbc.update(
                "UPDATE monitors SET deleted_at = now() WHERE project_id = ? AND name = 'Existing 1'",
                UUID.fromString(production));
        assertThat(create(ana, production, body("Monitor 51"))).hasStatus(201);
    }

    @Test
    void aNameIsUniqueInTheProjectWhateverItsCase() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String production = projectOf(ana, organization, "Production");
        String staging = projectOf(ana, organization, "Staging");
        monitorOf(ana, production, "Payments API");
        String donations = monitorOf(ana, production, "Donations API");

        assertThat(create(ana, production, body("PAYMENTS api")))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("conflict");
        assertThat(patch(ana, donations, "{\"name\": \"payments API\"}", null)).hasStatus(409);
        assertThat(get(ana, donations)).bodyJson().extractingPath("$.name").isEqualTo("Donations API");
        assertThat(create(ana, staging, body("Payments API"))).hasStatus(201);
    }

    @Test
    void theTimeoutStaysBelowTheIntervalWhenCreatingAndWhenChangingEitherOne() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");

        assertInvalidField(create(ana, project, """
                        {"name": "Slow", "url": "%s", "intervalSeconds": 30, "timeoutMs": 30000}""".formatted(HEALTH)), "timeoutMs");

        String patient = read(create(ana, project, """
                        {"name": "Patient", "url": "%s", "intervalSeconds": 60, "timeoutMs": 30000}""".formatted(HEALTH)), "$.id");
        assertInvalidField(patch(ana, patient, "{\"intervalSeconds\": 30}", null), "timeoutMs");
        assertThat(get(ana, patient))
                .bodyJson()
                .extractingPath("$.intervalSeconds")
                .isEqualTo(60);

        String frequent = read(create(ana, project, """
                        {"name": "Frequent", "url": "%s", "intervalSeconds": 30, "degradedThresholdMs": 2000}""".formatted(HEALTH)), "$.id");
        assertInvalidField(patch(ana, frequent, "{\"timeoutMs\": 30000}", null), "timeoutMs");
        assertInvalidField(patch(ana, frequent, "{\"timeoutMs\": 1500}", null), "degradedThresholdMs");
        assertThat(get(ana, frequent)).bodyJson().extractingPath("$.timeoutMs").isEqualTo(10_000);
    }

    @Test
    void aPatchOnlyChangesWhatItSendsAndANullDegradedThresholdTurnsItOff() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");
        String id = read(create(ana, project, """
                        {"name": "Payments API", "url": "%s", "httpMethod": "HEAD", "degradedThresholdMs": 2000}""".formatted(HEALTH)), "$.id");

        MvcTestResult empty = patch(ana, id, "{}", null);
        assertThat(empty).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(empty).bodyJson().extractingPath("$.degradedThresholdMs").isEqualTo(2000);

        MvcTestResult renamed = patch(ana, id, "{\"name\": \"Payments\", \"expectedStatus\": {\"max\": 204}}", null);
        assertThat(renamed).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"1\"");
        assertThat(renamed).bodyJson().extractingPath("$.expectedStatus").isEqualTo(Map.of("min", 200, "max", 204));
        assertThat(renamed).bodyJson().extractingPath("$.degradedThresholdMs").isEqualTo(2000);
        assertThat(renamed).bodyJson().extractingPath("$.httpMethod").isEqualTo("HEAD");

        MvcTestResult turnedOff = patch(ana, id, "{\"degradedThresholdMs\": null}", null);
        assertThat(turnedOff).hasStatus(200);
        assertThat(turnedOff).bodyJson().extractingPath("$.degradedThresholdMs").isNull();
        assertThat(turnedOff).bodyJson().extractingPath("$.name").isEqualTo("Payments");

        for (String nullField : List.of("name", "url", "intervalSeconds", "expectedStatus", "followRedirects")) {
            assertThat(patch(ana, id, "{\"%s\": null}".formatted(nullField), null))
                    .as(nullField)
                    .hasStatus(400);
        }
        assertThat(patch(ana, id, "{\"expectedStatus\": {\"min\": null}}", null))
                .hasStatus(400);
        assertThat(patch(ana, id, "{\"httpMethod\": \"POST\"}", null)).hasStatus(400);
        assertThat(patch(ana, id, "{\"failureThreshold\": 11}", null))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.errors[0].field")
                .isEqualTo("failureThreshold");
    }

    @Test
    void aStaleIfMatchIsAFailedPreconditionAndChangesNothing() {
        Caller ana = signedIn();
        String id = monitorOf(ana, projectOf(ana, organizationOf(ana), "Production"), "Payments API");

        assertThat(patch(ana, id, "{\"name\": \"Payments\"}", "\"0\""))
                .hasStatus(200)
                .headers()
                .hasValue(HttpHeaders.ETAG, "\"1\"");

        assertThat(patch(ana, id, "{\"name\": \"Lost update\"}", "\"0\""))
                .hasStatus(412)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("precondition-failed");
        assertThat(get(ana, id)).bodyJson().extractingPath("$.name").isEqualTo("Payments");
    }

    @Test
    void aShorterIntervalBringsTheNextCheckForwardUnlessThePauseKeepsItUnscheduled() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");
        String id = read(create(ana, project, """
                        {"name": "Payments API", "url": "%s", "intervalSeconds": 3600}""".formatted(HEALTH)), "$.id");
        // As the dispatcher would leave it after a check
        jdbc.update(
                "UPDATE monitor_state SET next_check_at = now() + interval '1 hour' WHERE monitor_id = ?",
                UUID.fromString(id));

        Instant before = Instant.now();
        MvcTestResult faster = patch(ana, id, "{\"intervalSeconds\": 60}", null);

        assertThat(faster).hasStatus(200);
        assertThat(Instant.parse(read(faster, "$.state.nextCheckAt")))
                .isBetween(before.plusSeconds(59), Instant.now().plusSeconds(60));
        assertThat(nextCheckAt(id)).isEqualTo(Instant.parse(read(faster, "$.state.nextCheckAt")));

        jdbc.update(
                "UPDATE monitor_state SET status = 'PAUSED', next_check_at = NULL WHERE monitor_id = ?",
                UUID.fromString(id));
        MvcTestResult paused = patch(ana, id, "{\"intervalSeconds\": 30}", null);

        assertThat(paused).hasStatus(200);
        assertThat(paused).bodyJson().extractingPath("$.state.status").isEqualTo("PAUSED");
        assertThat(paused).bodyJson().extractingPath("$.state.nextCheckAt").isNull();
        assertThat(nextCheckAt(id)).isNull();
    }

    @Test
    void listsAPageSortedByNameByDefaultFilteredByStatus() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");
        String charlie = monitorOf(ana, project, "Charlie");
        monitorOf(ana, project, "Alpha");
        monitorOf(ana, project, "Bravo");
        setStatus(charlie, "UP");

        assertThat(names(list(ana, project, Map.of("size", "2")))).isEqualTo(List.of("Alpha", "Bravo"));
        assertThat(names(list(ana, project, Map.of("sort", "createdAt,desc"))))
                .isEqualTo(List.of("Bravo", "Alpha", "Charlie"));
        assertThat(names(list(ana, project, Map.of("status", "UP")))).isEqualTo(List.of("Charlie"));
        assertThat(names(list(ana, project, Map.of("status", "PENDING")))).isEqualTo(List.of("Alpha", "Bravo"));
        assertThat(names(list(ana, project, Map.of("sort", "status,desc"))))
                .isEqualTo(List.of("Charlie", "Alpha", "Bravo"));
        assertThat(names(list(ana, project, Map.of("q", "a", "sort", "name,desc"))))
                .isEqualTo(List.of("Charlie", "Bravo", "Alpha"));
    }

    @Test
    void rejectsSortsFiltersAndPagesItDoesNotAllow() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");

        for (Map<String, String> query : List.of(
                Map.of("sort", "url"),
                Map.of("sort", "version"),
                Map.of("sort", "organizationId"),
                Map.of("status", "BROKEN"),
                Map.of("status", "up"),
                Map.of("size", "101"),
                Map.of("q", "a".repeat(101)))) {
            assertThat(list(ana, project, query))
                    .as("%s", query)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("invalid-parameter");
        }
        assertThat(get(ana, "not-a-uuid")).hasStatus(400);
    }

    /** {@code %} and {@code _} are not wildcards, and the escape character is a character like any other. */
    @Test
    void searchesTheNameLiterallyWhateverItsCase() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");
        for (String name : List.of("50% Off", "5000 off", "a_b", "axb", "back\\\\slash", "backslash")) {
            monitorOf(ana, project, name);
        }

        assertThat(names(list(ana, project, Map.of("q", "%")))).isEqualTo(List.of("50% Off"));
        assertThat(names(list(ana, project, Map.of("q", "_")))).isEqualTo(List.of("a_b"));
        assertThat(names(list(ana, project, Map.of("q", "\\")))).isEqualTo(List.of("back\\slash"));
        assertThat(names(list(ana, project, Map.of("q", "OFF")))).isEqualTo(List.of("50% Off", "5000 off"));
        assertThat(names(list(ana, project, Map.of("q", "   ")))).hasSize(6);
    }

    @Test
    void summarizesTheMonitorsOfAProjectByStatusWithEveryStatus() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String project = projectOf(ana, organization, "Production");
        String other = projectOf(ana, organization, "Staging");

        assertThat(summary(ana, project)).hasStatus(200).bodyJson().isEqualTo("""
                        {"total": 0, "byStatus": {"PENDING": 0, "UP": 0, "DEGRADED": 0, "DOWN": 0, "PAUSED": 0}}""");

        setStatus(monitorOf(ana, project, "Up"), "UP");
        setStatus(monitorOf(ana, project, "Down"), "DOWN");
        monitorOf(ana, project, "Pending");
        String deleted = monitorOf(ana, project, "Deleted");
        jdbc.update("UPDATE monitors SET deleted_at = now() WHERE id = ?", UUID.fromString(deleted));
        monitorOf(ana, other, "Elsewhere");

        assertThat(summary(ana, project)).bodyJson().isEqualTo("""
                {"total": 3, "byStatus": {"PENDING": 1, "UP": 1, "DEGRADED": 0, "DOWN": 1, "PAUSED": 0}}""");
    }

    @Test
    void theMonitorsOfADeletedProjectAreGone() {
        Caller ana = signedIn();
        String project = projectOf(ana, organizationOf(ana), "Production");
        String id = monitorOf(ana, project, "Payments API");

        assertThat(mvc.delete()
                        .uri("/api/v1/projects/" + project)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ana.token())
                        .exchange())
                .hasStatus(204);

        assertThat(get(ana, id))
                .hasStatus(404)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("monitor " + id + " was not found");
        assertThat(patch(ana, id, "{\"name\": \"Revived\"}", null)).hasStatus(404);
        assertThat(create(ana, project, body("Late"))).hasStatus(404);
    }

    /** The example of the README, without headers (OW-022), entirely through the API. */
    @Test
    void theCharityLinkExampleCanBeCreatedThroughTheApi() {
        Caller ana = signedIn();
        String charityLink = organizationOf(ana);
        String production = projectOf(ana, charityLink, "Production");
        projectOf(ana, charityLink, "Staging");

        for (String name : List.of("Authentication API", "Donations API", "Payments API", "Notifications API")) {
            assertThat(create(ana, production, """
                            {"name": "%s", "url": "%s", "httpMethod": "GET", "intervalSeconds": 60,
                             "timeoutMs": 10000, "expectedStatus": {"min": 200, "max": 299}}""".formatted(name, HEALTH))).hasStatus(201);
        }

        assertThat(names(list(ana, production, Map.of())))
                .isEqualTo(List.of("Authentication API", "Donations API", "Notifications API", "Payments API"));
        assertThat(summary(ana, production))
                .bodyJson()
                .extractingPath("$.byStatus.PENDING")
                .isEqualTo(4);
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/projects/{projectId}/monitors'].post.responses")
                .asMap()
                .containsKeys("201", "400", "401", "403", "404", "409", "422");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/monitors/{monitorId}'].patch.responses")
                .asMap()
                .containsKeys("200", "400", "401", "403", "404", "409", "412", "422");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/projects/{projectId}/monitors'].get.parameters[*].name")
                .asArray()
                .contains("status", "q", "page", "size", "sort");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.components.schemas.UpdateMonitorRequest.properties.degradedThresholdMs.maximum")
                .isEqualTo(30000);
    }

    /** A signed-in user: the access token and the id. */
    private record Caller(String token, String id) {}

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
        MvcTestResult result = create(caller, projectId, body(name));
        assertThat(result).as("monitor %s", name).hasStatus(201);
        return read(result, "$.id");
    }

    /** Straight into the tables, with its state: as many as a quota needs, without a request each. */
    private void insertMonitor(String organizationId, String projectId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO monitors (id, organization_id, project_id, name, url, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'https://api.example.com/health', now(), now())""", id, UUID.fromString(organizationId), UUID.fromString(projectId), name);
        jdbc.update("""
                INSERT INTO monitor_state (monitor_id, status_changed_at, next_check_at, updated_at)
                VALUES (?, now(), now(), now())""", id);
    }

    /** As the engine would leave it after some checks. */
    private void setStatus(String monitorId, String status) {
        jdbc.update("UPDATE monitor_state SET status = ? WHERE monitor_id = ?", status, UUID.fromString(monitorId));
    }

    private @Nullable Instant nextCheckAt(String monitorId) {
        Timestamp next = jdbc.queryForObject(
                "SELECT next_check_at FROM monitor_state WHERE monitor_id = ?",
                Timestamp.class,
                UUID.fromString(monitorId));
        return next == null ? null : next.toInstant();
    }

    private static String body(String name) {
        return "{\"name\": \"%s\", \"url\": \"%s\"}".formatted(name, HEALTH);
    }

    private MvcTestResult create(Caller caller, String projectId, String body) {
        return as(caller, json(mvc.post(), "/api/v1/projects/" + projectId + "/monitors", body))
                .exchange();
    }

    private MvcTestResult list(Caller caller, String projectId, Map<String, String> query) {
        MockMvcRequestBuilder request = as(caller, mvc.get().uri("/api/v1/projects/" + projectId + "/monitors"));
        query.forEach(request::param);
        return request.exchange();
    }

    private MvcTestResult summary(Caller caller, String projectId) {
        return as(caller, mvc.get().uri("/api/v1/projects/" + projectId + "/monitors/summary"))
                .exchange();
    }

    private MvcTestResult get(Caller caller, String id) {
        return as(caller, mvc.get().uri("/api/v1/monitors/" + id)).exchange();
    }

    private MvcTestResult patch(Caller caller, String id, String body, @Nullable String ifMatch) {
        MockMvcRequestBuilder request = as(caller, json(mvc.patch(), "/api/v1/monitors/" + id, body));
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
    }

    private static MockMvcRequestBuilder as(Caller caller, MockMvcRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token());
    }

    private static MockMvcRequestBuilder json(MockMvcRequestBuilder request, String uri, String body) {
        return request.uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static void assertInvalidField(MvcTestResult result, String field) {
        assertThat(result).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("validation-error");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo(field);
    }

    private static List<String> names(MvcTestResult result) {
        assertThat(result).hasStatus(200);
        return JsonPath.read(content(result), "$.items[*].name");
    }

    private static String read(MvcTestResult result, String path) {
        return JsonPath.read(content(result), path);
    }

    private static String content(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
