package io.github.ricardoord.opswatch.monitoring.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorCheckRepository;
import io.github.ricardoord.opswatch.organization.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

/** {@code /api/v1/monitors/{monitorId}/checks} and {@code /stats}, through the real security chain. */
@IntegrationTest
class CheckApiIT {

    private static final String PASSWORD = "correct horse battery";
    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MonitorCheckRepository checks;

    @Autowired
    private Clock clock;

    /** Five checks two by two: every check once, newest first, and no cursor after the last page. */
    @Test
    void walksTheHistoryNewestFirstByCursor() {
        Caller ana = signedIn();
        String monitor = monitorOf(ana);
        List<Instant> recorded = recordMinutely(monitor, 5);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MvcTestResult page = checks(
                    ana, monitor, cursor == null ? Map.of("limit", "2") : Map.of("limit", "2", "cursor", cursor));
            assertThat(page).hasStatus(200);
            seen.addAll(JsonPath.read(content(page), "$.items[*].checkedAt"));
            cursor = JsonPath.read(content(page), "$.nextCursor");
            pages++;
            // A cursor that does not move would page forever
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(seen)
                .containsExactlyElementsOf(
                        recorded.stream().map(Instant::toString).toList());
    }

    @Test
    void showsEachCheckAsItWasRecorded() {
        Caller ana = signedIn();
        String monitor = monitorOf(ana);
        Instant checkedAt = clock.instant().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        checks.insert(
                UUID.fromString(monitor),
                checkedAt,
                CheckOutcome.down(FailureReason.TIMEOUT, null, null, "no response within the timeout"));

        assertThat(checks(ana, monitor, Map.of())).hasStatus(200).bodyJson().isEqualTo("""
                {"items": [{"checkedAt": "%s", "status": "DOWN", "httpStatus": null, "responseTimeMs": null,
                            "failureReason": "TIMEOUT", "errorDetail": "no response within the timeout"}],
                 "nextCursor": null}""".formatted(checkedAt));
    }

    @Test
    void filtersByStatusAndByTime() {
        Caller ana = signedIn();
        String monitor = monitorOf(ana);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID id = UUID.fromString(monitor);
        checks.insert(id, now.minusSeconds(60), CheckOutcome.up(200, Duration.ofMillis(100)));
        checks.insert(id, now.minusSeconds(120), CheckOutcome.degraded(200, Duration.ofMillis(900)));
        checks.insert(id, now.minusSeconds(180), CheckOutcome.down(FailureReason.UNEXPECTED_STATUS, 503, null, null));
        checks.insert(id, now.minusSeconds(240), CheckOutcome.up(200, Duration.ofMillis(100)));

        assertThat(statuses(checks(ana, monitor, Map.of("status", "DOWN,DEGRADED"))))
                .containsExactly("DEGRADED", "DOWN");
        // from inclusive, to exclusive
        assertThat(statuses(checks(
                        ana,
                        monitor,
                        Map.of(
                                "from",
                                now.minusSeconds(180).toString(),
                                "to",
                                now.minusSeconds(60).toString()))))
                .containsExactly("DEGRADED", "DOWN");
    }

    @Test
    void rejectsParametersItDoesNotAllow() {
        Caller ana = signedIn();
        String monitor = monitorOf(ana);
        String now = clock.instant().toString();

        for (Map<String, String> query : List.of(
                Map.of("limit", "201"),
                Map.of("limit", "0"),
                Map.of("limit", "many"),
                Map.of("cursor", "not-a-cursor"),
                Map.of("cursor", "eyJjIjoieWVzdGVyZGF5In0"),
                Map.of("status", "PAUSED"),
                Map.of("status", "up"),
                Map.of("from", "yesterday"),
                Map.of("from", now, "to", now))) {
            assertThat(checks(ana, monitor, query))
                    .as("%s", query)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("invalid-parameter");
        }
        for (String window : List.of("1h", "90d", "24H", "7 d")) {
            assertThat(stats(ana, monitor, window))
                    .as("window %s", window)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("invalid-parameter");
        }
        assertThat(checks(ana, monitor, Map.of("limit", "200"))).hasStatus(200);
    }

    @Test
    void sumsUpTheLastDayByDefault() {
        Caller ana = signedIn();
        String monitor = monitorOf(ana);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID id = UUID.fromString(monitor);
        checks.insert(id, now.minusSeconds(60), CheckOutcome.up(200, Duration.ofMillis(100)));
        checks.insert(id, now.minusSeconds(120), CheckOutcome.down(FailureReason.TIMEOUT, null, null, null));

        MvcTestResult result = stats(ana, monitor, null);

        assertThat(result).hasStatus(200).bodyJson().isLenientlyEqualTo("""
                {"window": "24h", "totalChecks": 2, "up": 1, "degraded": 0, "down": 1, "uptimePercent": 50.000,
                 "responseTimeMs": {"avg": 100, "p50": 100, "p95": 100, "p99": 100},
                 "failuresByReason": {"TIMEOUT": 1}}""");
        Instant from = Instant.parse(JsonPath.read(content(result), "$.from"));
        Instant to = Instant.parse(JsonPath.read(content(result), "$.to"));
        assertThat(Duration.between(from, to)).isEqualTo(Duration.ofHours(24));
        assertThat(stats(ana, monitor, "30d"))
                .bodyJson()
                .extractingPath("$.window")
                .isEqualTo("30d");
    }

    @Test
    void aViewerReadsTheHistoryAndTheStatistics() {
        Caller owner = signedIn();
        String organization = organizationOf(owner);
        String monitor = monitorOf(owner, projectOf(owner, organization));
        Caller viewer = memberOf(organization, Role.VIEWER);

        assertThat(checks(viewer, monitor, Map.of())).hasStatus(200);
        assertThat(stats(viewer, monitor, "7d")).hasStatus(200);
    }

    /**
     * Someone from another organization gets exactly what a missing monitor gets. A cursor of someone else's monitor
     * only moves within the monitor of the path: it carries a date, nothing that chooses the monitor.
     */
    @Test
    void someoneFromAnotherOrganizationGets404AsForAMissingMonitorAndACursorCannotReachIt() {
        Caller ana = signedIn();
        String anas = monitorOf(ana);
        recordMinutely(anas, 3);
        Caller mallory = signedIn();
        String mallorys = monitorOf(mallory);
        recordMinutely(mallorys, 3);
        String missing = UUID.randomUUID().toString();

        for (String target : List.of(anas, missing)) {
            for (MvcTestResult attempt : List.of(checks(mallory, target, Map.of()), stats(mallory, target, "24h"))) {
                assertThat(attempt)
                        .hasStatus(404)
                        .bodyJson()
                        .extractingPath("$.detail")
                        .isEqualTo("monitor " + target + " was not found");
            }
        }

        String anasCursor = JsonPath.read(content(checks(ana, anas, Map.of("limit", "1"))), "$.nextCursor");
        MvcTestResult withAnasCursor = checks(mallory, mallorys, Map.of("cursor", anasCursor));
        assertThat(withAnasCursor).hasStatus(200);
        assertThat(JsonPath.<List<String>>read(content(withAnasCursor), "$.items[*].checkedAt"))
                .allSatisfy(
                        checkedAt -> assertThat(isCheckOf(mallorys, checkedAt)).isTrue());
    }

    @Test
    void theChecksOfADeletedMonitorAreGone() {
        Caller ana = signedIn();
        String monitor = monitorOf(ana);
        recordMinutely(monitor, 1);

        assertThat(as(ana, mvc.delete().uri("/api/v1/monitors/" + monitor)).exchange())
                .hasStatus(204);

        assertThat(checks(ana, monitor, Map.of())).hasStatus(404);
        assertThat(stats(ana, monitor, "24h")).hasStatus(404);
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/monitors/{monitorId}/checks'].get.parameters[*].name")
                .asArray()
                .containsExactlyInAnyOrder("monitorId", "status", "from", "to", "limit", "cursor");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/monitors/{monitorId}/checks'].get.responses")
                .asMap()
                .containsKeys("200", "400", "401", "404");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/monitors/{monitorId}/stats'].get.parameters[*].name")
                .asArray()
                .containsExactlyInAnyOrder("monitorId", "window");
    }

    /** @return their instants, newest first */
    private List<Instant> recordMinutely(String monitor, int count) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Instant> recorded = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Instant checkedAt = now.minus(Duration.ofMinutes(i));
            checks.insert(UUID.fromString(monitor), checkedAt, CheckOutcome.up(200, Duration.ofMillis(100)));
            recorded.add(checkedAt);
        }
        return recorded;
    }

    private boolean isCheckOf(String monitor, String checkedAt) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM monitor_checks WHERE monitor_id = ? AND checked_at = ?::timestamptz",
                Long.class,
                UUID.fromString(monitor),
                checkedAt);
        return count != null && count == 1;
    }

    private static List<String> statuses(MvcTestResult result) {
        assertThat(result).hasStatus(200);
        return JsonPath.read(content(result), "$.items[*].status");
    }

    private MvcTestResult checks(Caller caller, String monitor, Map<String, String> query) {
        MockMvcRequestBuilder request = as(caller, mvc.get().uri("/api/v1/monitors/" + monitor + "/checks"));
        query.forEach(request::param);
        return request.exchange();
    }

    private MvcTestResult stats(Caller caller, String monitor, @Nullable String window) {
        MockMvcRequestBuilder request = as(caller, mvc.get().uri("/api/v1/monitors/" + monitor + "/stats"));
        if (window != null) {
            request.param("window", window);
        }
        return request.exchange();
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

    private String projectOf(Caller caller, String organizationId) {
        MvcTestResult result = as(
                        caller,
                        json(
                                mvc.post(),
                                "/api/v1/organizations/" + organizationId + "/projects",
                                "{\"name\": \"Production\"}"))
                .exchange();
        assertThat(result).hasStatus(201);
        return read(result, "$.id");
    }

    /** In an organization and a project of its own. */
    private String monitorOf(Caller caller) {
        return monitorOf(caller, projectOf(caller, organizationOf(caller)));
    }

    private String monitorOf(Caller caller, String projectId) {
        MvcTestResult result = as(
                        caller,
                        json(
                                mvc.post(),
                                "/api/v1/projects/" + projectId + "/monitors",
                                "{\"name\": \"Payments API\", \"url\": \"%s\"}".formatted(HEALTH)))
                .exchange();
        assertThat(result).hasStatus(201);
        return read(result, "$.id");
    }

    private static MockMvcRequestBuilder as(Caller caller, MockMvcRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token());
    }

    private static MockMvcRequestBuilder json(MockMvcRequestBuilder request, String uri, String body) {
        return request.uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
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
