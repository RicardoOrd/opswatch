package io.github.ricardoord.opswatch;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.identity.security.AccessTokenIssuer;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Who can call each endpoint of the API (docs/testing/testing-strategy.md#pruebas-de-seguridad), against the real
 * application. Every case gets a fresh organization with one member of each role, so no case depends on another.
 *
 * <p>An endpoint without its row, or a row without its endpoint, fails {@link #everyEndpointOfTheApiHasItsRow}; a
 * change of permissions that the table does not reflect fails {@link #answersEachCallerAsTheMatrixSays}.
 */
@IntegrationTest
class EndpointAuthorizationMatrixIT {

    /**
     * The status each caller gets, in the order of {@link Caller}: the {@code OWNER}, {@code ADMIN}, {@code MEMBER} and
     * {@code VIEWER} of the organization in the path, a signed-in user who is not a member, and someone without a
     * token. An endpoint without an organization in the path answers every signed-in user alike.
     */
    private static final String MATRIX = """
            POST   /api/v1/auth/register                            201 201 201 201 201 201
            POST   /api/v1/auth/login                               200 200 200 200 200 200
            POST   /api/v1/auth/refresh                             200 200 200 200 200 200
            POST   /api/v1/auth/logout                              204 204 204 204 204 204
            GET    /api/v1/me                                       200 200 200 200 200 401
            PATCH  /api/v1/me                                       200 200 200 200 200 401
            POST   /api/v1/me/password                              204 204 204 204 204 401
            POST   /api/v1/organizations                            201 201 201 201 201 401
            GET    /api/v1/organizations                            200 200 200 200 200 401
            GET    /api/v1/organizations/{orgId}                    200 200 200 200 404 401
            PATCH  /api/v1/organizations/{orgId}                    200 200 403 403 404 401
            DELETE /api/v1/organizations/{orgId}                    204 403 403 403 404 401
            GET    /api/v1/organizations/{orgId}/members            200 200 200 200 404 401
            POST   /api/v1/organizations/{orgId}/members            201 201 403 403 404 401
            PATCH  /api/v1/organizations/{orgId}/members/{userId}   200 200 403 403 404 401
            DELETE /api/v1/organizations/{orgId}/members/{userId}   204 204 403 403 404 401
            POST   /api/v1/organizations/{orgId}/projects           201 201 403 403 404 401
            GET    /api/v1/organizations/{orgId}/projects           200 200 200 200 404 401
            GET    /api/v1/projects/{projectId}                     200 200 200 200 404 401
            PATCH  /api/v1/projects/{projectId}                     200 200 403 403 404 401
            DELETE /api/v1/projects/{projectId}                     204 204 403 403 404 401
            POST   /api/v1/projects/{projectId}/monitors            201 201 201 403 404 401
            GET    /api/v1/projects/{projectId}/monitors            200 200 200 200 404 401
            GET    /api/v1/projects/{projectId}/monitors/summary    200 200 200 200 404 401
            GET    /api/v1/monitors/{monitorId}                     200 200 200 200 404 401
            PATCH  /api/v1/monitors/{monitorId}                     200 200 200 403 404 401
            POST   /api/v1/monitors/{monitorId}/pause               200 200 200 403 404 401
            POST   /api/v1/monitors/{monitorId}/resume              200 200 200 403 404 401
            DELETE /api/v1/monitors/{monitorId}                     204 204 204 403 404 401
            GET    /api/v1/monitors/{monitorId}/checks              200 200 200 200 404 401
            GET    /api/v1/monitors/{monitorId}/stats               200 200 200 200 404 401
            GET    /api/v1/organizations/{orgId}/incidents          200 200 200 200 404 401
            GET    /api/v1/incidents/{incidentId}                   200 200 200 200 404 401
            POST   /api/v1/incidents/{incidentId}/acknowledge       200 200 200 403 404 401
            """;

    private static final Pattern ROW = Pattern.compile("(GET|POST|PATCH|DELETE)\\s+(\\S+)((?:\\s+\\d{3}){6})");
    private static final String PASSWORD = "correct horse battery";
    private static final String COOKIE = "opswatch_refresh";
    private static final Pattern COOKIE_VALUE = Pattern.compile(COOKIE + "=([^;]*)");

    /**
     * A valid request to each endpoint, so that its status only depends on who calls: {@code {userId}} is a
     * {@code MEMBER} of the organization, the subject of every change to members.
     */
    private final Map<String, BiFunction<MockMvcTester, Fixture, MockMvcRequestBuilder>> requests = requests();

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccessTokenIssuer tokens;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /** Who calls. */
    enum Caller {
        OWNER,
        ADMIN,
        MEMBER,
        VIEWER,
        NOT_A_MEMBER,
        ANONYMOUS
    }

    @ParameterizedTest(name = "{0} as {1} → {2}")
    @MethodSource("cases")
    void answersEachCallerAsTheMatrixSays(String endpoint, Caller caller, int expected) throws Exception {
        Fixture fixture = fixture();
        MockMvcRequestBuilder request = requests.get(endpoint).apply(mvc, fixture);
        String token = fixture.tokens().get(caller);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }

        MvcTestResult result = request.exchange();

        assertThat(result.getResponse().getStatus())
                .as("%s as %s: %s", endpoint, caller, result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    @Test
    void everyEndpointOfTheApiHasItsRow() {
        Set<String> registered = new TreeSet<>();
        for (RequestMappingInfo mapping : handlerMapping.getHandlerMethods().keySet()) {
            for (String path : mapping.getPatternValues()) {
                if (path.startsWith("/api/")) {
                    Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
                    assertThat(methods)
                            .as("%s must declare its HTTP method", path)
                            .isNotEmpty();
                    methods.forEach(method -> registered.add(method + " " + path));
                }
            }
        }

        assertThat(matrix().keySet()).as("rows of MATRIX").containsExactlyInAnyOrderElementsOf(registered);
        assertThat(requests.keySet()).as("requests").containsExactlyInAnyOrderElementsOf(registered);
    }

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        matrix().forEach((endpoint, expected) ->
                expected.forEach((caller, status) -> cases.add(Arguments.of(endpoint, caller, status))));
        return cases.stream();
    }

    /** The rows of {@link #MATRIX}, by {@code METHOD path}. */
    private static Map<String, Map<Caller, Integer>> matrix() {
        Map<String, Map<Caller, Integer>> rows = new LinkedHashMap<>();
        for (String line : MATRIX.strip().split("\n")) {
            Matcher row = ROW.matcher(line.strip());
            assertThat(row.matches()).as("row of MATRIX: %s", line).isTrue();
            String[] statuses = row.group(3).strip().split("\\s+");
            Map<Caller, Integer> expected = new EnumMap<>(Caller.class);
            for (Caller caller : Caller.values()) {
                expected.put(caller, Integer.parseInt(statuses[caller.ordinal()]));
            }
            assertThat(rows.put(row.group(1) + " " + row.group(2), expected))
                    .as("repeated row: %s", line)
                    .isNull();
        }
        return rows;
    }

    private static Map<String, BiFunction<MockMvcTester, Fixture, MockMvcRequestBuilder>> requests() {
        Map<String, BiFunction<MockMvcTester, Fixture, MockMvcRequestBuilder>> requests = new LinkedHashMap<>();
        requests.put(
                "POST /api/v1/auth/register",
                (mvc, fixture) -> json(mvc.post(), "/api/v1/auth/register", """
                {"email": "%s", "displayName": "Ana", "password": "%s"}""".formatted(uniqueEmail(), PASSWORD)));
        requests.put("POST /api/v1/auth/login", (mvc, fixture) -> login(mvc, fixture.subjectEmail()));
        requests.put(
                "POST /api/v1/auth/refresh",
                (mvc, fixture) -> withRefreshCookie(mvc, fixture, mvc.post().uri("/api/v1/auth/refresh")));
        requests.put(
                "POST /api/v1/auth/logout",
                (mvc, fixture) -> withRefreshCookie(mvc, fixture, mvc.post().uri("/api/v1/auth/logout")));
        requests.put("GET /api/v1/me", (mvc, fixture) -> mvc.get().uri("/api/v1/me"));
        requests.put(
                "PATCH /api/v1/me",
                (mvc, fixture) -> json(mvc.patch(), "/api/v1/me", "{\"displayName\": \"Renamed\"}"));
        requests.put(
                "POST /api/v1/me/password",
                (mvc, fixture) -> json(mvc.post(), "/api/v1/me/password", """
                {"currentPassword": "%s", "newPassword": "another long passphrase"}""".formatted(PASSWORD)));
        requests.put(
                "POST /api/v1/organizations",
                (mvc, fixture) -> json(mvc.post(), "/api/v1/organizations", "{\"name\": \"Another\"}"));
        requests.put("GET /api/v1/organizations", (mvc, fixture) -> mvc.get().uri("/api/v1/organizations"));
        requests.put(
                "GET /api/v1/organizations/{orgId}",
                (mvc, fixture) -> mvc.get().uri("/api/v1/organizations/{orgId}", fixture.organization()));
        requests.put(
                "PATCH /api/v1/organizations/{orgId}",
                (mvc, fixture) -> json(
                        mvc.patch(), "/api/v1/organizations/" + fixture.organization(), "{\"name\": \"Renamed\"}"));
        requests.put(
                "DELETE /api/v1/organizations/{orgId}",
                (mvc, fixture) -> mvc.delete().uri("/api/v1/organizations/{orgId}", fixture.organization()));
        requests.put(
                "GET /api/v1/organizations/{orgId}/members",
                (mvc, fixture) -> mvc.get().uri("/api/v1/organizations/{orgId}/members", fixture.organization()));
        requests.put(
                "POST /api/v1/organizations/{orgId}/members",
                (mvc, fixture) -> json(
                        mvc.post(),
                        "/api/v1/organizations/" + fixture.organization() + "/members",
                        "{\"email\": \"%s\", \"role\": \"VIEWER\"}".formatted(fixture.newcomerEmail())));
        requests.put(
                "PATCH /api/v1/organizations/{orgId}/members/{userId}",
                (mvc, fixture) -> json(
                        mvc.patch(),
                        "/api/v1/organizations/" + fixture.organization() + "/members/" + fixture.subject(),
                        "{\"role\": \"VIEWER\"}"));
        requests.put(
                "DELETE /api/v1/organizations/{orgId}/members/{userId}",
                (mvc, fixture) -> mvc.delete()
                        .uri(
                                "/api/v1/organizations/{orgId}/members/{userId}",
                                fixture.organization(),
                                fixture.subject()));
        requests.put(
                "POST /api/v1/organizations/{orgId}/projects",
                (mvc, fixture) -> json(
                        mvc.post(),
                        "/api/v1/organizations/" + fixture.organization() + "/projects",
                        "{\"name\": \"Staging\"}"));
        requests.put(
                "GET /api/v1/organizations/{orgId}/projects",
                (mvc, fixture) -> mvc.get().uri("/api/v1/organizations/{orgId}/projects", fixture.organization()));
        requests.put(
                "GET /api/v1/projects/{projectId}",
                (mvc, fixture) -> mvc.get().uri("/api/v1/projects/{projectId}", fixture.project()));
        requests.put(
                "PATCH /api/v1/projects/{projectId}",
                (mvc, fixture) ->
                        json(mvc.patch(), "/api/v1/projects/" + fixture.project(), "{\"name\": \"Renamed\"}"));
        requests.put(
                "DELETE /api/v1/projects/{projectId}",
                (mvc, fixture) -> mvc.delete().uri("/api/v1/projects/{projectId}", fixture.project()));
        requests.put(
                "POST /api/v1/projects/{projectId}/monitors",
                (mvc, fixture) -> json(
                        mvc.post(),
                        "/api/v1/projects/" + fixture.project() + "/monitors",
                        "{\"name\": \"Payments API\", \"url\": \"https://%s/health\"}"
                                .formatted(TestHostResolver.PUBLIC_HOST)));
        requests.put(
                "GET /api/v1/projects/{projectId}/monitors",
                (mvc, fixture) -> mvc.get().uri("/api/v1/projects/{projectId}/monitors", fixture.project()));
        requests.put(
                "GET /api/v1/projects/{projectId}/monitors/summary",
                (mvc, fixture) -> mvc.get().uri("/api/v1/projects/{projectId}/monitors/summary", fixture.project()));
        requests.put(
                "GET /api/v1/monitors/{monitorId}",
                (mvc, fixture) -> mvc.get().uri("/api/v1/monitors/{monitorId}", fixture.monitor()));
        requests.put(
                "PATCH /api/v1/monitors/{monitorId}",
                (mvc, fixture) ->
                        json(mvc.patch(), "/api/v1/monitors/" + fixture.monitor(), "{\"name\": \"Renamed\"}"));
        requests.put(
                "POST /api/v1/monitors/{monitorId}/pause",
                (mvc, fixture) -> mvc.post().uri("/api/v1/monitors/{monitorId}/pause", fixture.monitor()));
        requests.put(
                "POST /api/v1/monitors/{monitorId}/resume",
                (mvc, fixture) -> mvc.post().uri("/api/v1/monitors/{monitorId}/resume", fixture.pausedMonitor()));
        requests.put(
                "DELETE /api/v1/monitors/{monitorId}",
                (mvc, fixture) -> mvc.delete().uri("/api/v1/monitors/{monitorId}", fixture.monitor()));
        requests.put(
                "GET /api/v1/monitors/{monitorId}/checks",
                (mvc, fixture) -> mvc.get().uri("/api/v1/monitors/{monitorId}/checks", fixture.monitor()));
        requests.put(
                "GET /api/v1/monitors/{monitorId}/stats",
                (mvc, fixture) -> mvc.get().uri("/api/v1/monitors/{monitorId}/stats", fixture.monitor()));
        requests.put(
                "GET /api/v1/organizations/{orgId}/incidents",
                (mvc, fixture) -> mvc.get().uri("/api/v1/organizations/{orgId}/incidents", fixture.organization()));
        requests.put(
                "GET /api/v1/incidents/{incidentId}",
                (mvc, fixture) -> mvc.get().uri("/api/v1/incidents/{incidentId}", fixture.incident()));
        requests.put(
                "POST /api/v1/incidents/{incidentId}/acknowledge",
                (mvc, fixture) -> json(
                        mvc.post(),
                        "/api/v1/incidents/" + fixture.incident() + "/acknowledge",
                        "{\"note\": \"Looking into it\"}"));
        return requests;
    }

    private static MockMvcRequestBuilder json(MockMvcRequestBuilder request, String uri, String body) {
        return request.uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockMvcRequestBuilder login(MockMvcTester mvc, String email) {
        return json(mvc.post(), "/api/v1/auth/login", """
                {"email": "%s", "password": "%s"}""".formatted(email, PASSWORD));
    }

    /** Signs the subject in first: refresh and logout need a session. */
    private static MockMvcRequestBuilder withRefreshCookie(
            MockMvcTester mvc, Fixture fixture, MockMvcRequestBuilder request) {
        MvcTestResult login = login(mvc, fixture.subjectEmail()).exchange();
        assertThat(login).hasStatus(200);
        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher cookie = COOKIE_VALUE.matcher(setCookie == null ? "" : setCookie);
        assertThat(cookie.find()).isTrue();
        return request.contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, TestJwtKeys.ISSUER)
                .cookie(new Cookie(COOKIE, cookie.group(1)));
    }

    /**
     * An organization with one member of each role and a project with a monitor and its open incident, a user who is not a member, a
     * {@code MEMBER} to change or remove and a user to add. Straight into the tables, with the tokens issued directly: nearly a hundred cases would
     * otherwise mean hundreds of registrations.
     */
    private Fixture fixture() {
        String passwordHash = passwordEncoder.encode(PASSWORD);
        UUID organization = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                organization);
        Map<Caller, @Nullable String> callerTokens = new EnumMap<>(Caller.class);
        for (Caller caller : Arrays.asList(Caller.OWNER, Caller.ADMIN, Caller.MEMBER, Caller.VIEWER)) {
            UUID user = insertUser(uniqueEmail(), passwordHash);
            insertMembership(organization, user, caller.name());
            callerTokens.put(caller, tokens.issue(user).value());
        }
        callerTokens.put(
                Caller.NOT_A_MEMBER,
                tokens.issue(insertUser(uniqueEmail(), passwordHash)).value());
        callerTokens.put(Caller.ANONYMOUS, null);
        String subjectEmail = uniqueEmail();
        UUID subject = insertUser(subjectEmail, passwordHash);
        insertMembership(organization, subject, "MEMBER");
        String newcomerEmail = uniqueEmail();
        insertUser(newcomerEmail, passwordHash);
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, 'Production', now(), now())""", project, organization);
        UUID monitor = insertMonitor(organization, project, "Authentication API", false);
        UUID pausedMonitor = insertMonitor(organization, project, "Donations API", true);
        UUID incident = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incidents (id, organization_id, project_id, monitor_id, monitor_name, status, cause, opened_at,
                                       created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Authentication API', 'OPEN', 'TIMEOUT', now(), now(), now())""", incident, organization, project, monitor);
        return new Fixture(
                organization,
                project,
                monitor,
                pausedMonitor,
                incident,
                callerTokens,
                subject,
                subjectEmail,
                newcomerEmail);
    }

    private UUID insertMonitor(UUID organization, UUID project, String name, boolean paused) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO monitors (id, organization_id, project_id, name, url, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'https://api.example.com/health', now(), now())""", id, organization, project, name);
        String status = paused ? "PAUSED" : "PENDING";
        jdbc.update("""
                INSERT INTO monitor_state (monitor_id, status, status_changed_at, next_check_at, updated_at)
                VALUES (?, ?, now(), CASE WHEN ? = 'PAUSED' THEN NULL ELSE now() END, now())""", id, status, status);
        return id;
    }

    private UUID insertUser(String email, String passwordHash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', ?, now(), now())""", id, email, passwordHash);
        return id;
    }

    private void insertMembership(UUID organization, UUID user, String role) {
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", organization, user, role);
    }

    /**
     * @param project a project of the organization
     * @param monitor a monitor of the project, scheduled
     * @param pausedMonitor a paused monitor of the project, which can be resumed
     * @param incident an open incident of {@code monitor}, which can be acknowledged
     * @param tokens the access token of each caller; none for {@link Caller#ANONYMOUS}
     * @param subject a {@code MEMBER} that the member endpoints change or remove
     * @param newcomerEmail a user with an account who is not a member yet
     */
    private record Fixture(
            UUID organization,
            UUID project,
            UUID monitor,
            UUID pausedMonitor,
            UUID incident,
            Map<Caller, @Nullable String> tokens,
            UUID subject,
            String subjectEmail,
            String newcomerEmail) {}
}
