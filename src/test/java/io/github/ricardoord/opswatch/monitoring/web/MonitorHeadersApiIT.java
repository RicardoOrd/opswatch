package io.github.ricardoord.opswatch.monitoring.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestEncryptionKeys;
import io.github.ricardoord.opswatch.TestHostResolver;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The headers of the monitors (OW-022): write-only through the API, encrypted in the database, absent from the logs,
 * and checked against layer 4 of the SSRF protection on saving.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class MonitorHeadersApiIT {

    private static final String PASSWORD = "correct horse battery";
    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoggingSystem loggingSystem;

    @Test
    void theValuesAreWriteOnly() {
        Caller ana = signedIn();
        String project = projectOf(ana);
        String sentinel = sentinel();

        MvcTestResult created = create(ana, project, "Payments API", """
                [{"name": "Authorization", "value": "Bearer %s"}, {"name": "X-Empty", "value": " "}]""".formatted(sentinel));

        assertThat(created).hasStatus(201);
        String id = read(created, "$.id");
        for (MvcTestResult read : List.of(created, get(ana, id), list(ana, project))) {
            assertThat(content(read)).doesNotContain(sentinel);
        }
        assertThat(created)
                .bodyJson()
                .extractingPath("$.headers")
                .isEqualTo(List.of(headerWithoutValue("Authorization", true), headerWithoutValue("X-Empty", false)));
        assertThat(get(ana, id))
                .bodyJson()
                .extractingPath("$.headers[0].hasValue")
                .isEqualTo(true);
        assertThat(list(ana, project))
                .bodyJson()
                .extractingPath("$.items[0].headers[0].value")
                .isNull();
    }

    @Test
    void theDatabaseOnlyHoldsCiphertext() {
        Caller ana = signedIn();
        String sentinel = sentinel();
        String id = read(create(ana, projectOf(ana), "Payments API", """
                        [{"name": "X-Api-Key", "value": "%s"}]""".formatted(sentinel)), "$.id");

        byte[] stored = storedHeaders(id);

        assertThat(stored).isNotNull();
        assertThat(Byte.toUnsignedInt(stored[0])).isEqualTo(TestEncryptionKeys.ACTIVE_KEY_ID);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1))
                .doesNotContain(sentinel)
                .doesNotContain("X-Api-Key");
    }

    /** Every layer at TRACE, the bodies of the requests and the parameters of the SQL included: none prints a value. */
    @Test
    void noLogLineCarriesAValue(CapturedOutput output) {
        Caller ana = signedIn();
        String project = projectOf(ana);
        String sentinel = sentinel();
        List<String> loggers = List.of(
                "io.github.ricardoord.opswatch",
                "org.springframework.web",
                "org.hibernate.SQL",
                "org.hibernate.orm.jdbc.bind");
        loggers.forEach(logger -> loggingSystem.setLogLevel(logger, LogLevel.TRACE));
        try {
            String id = read(create(ana, project, "Payments API", """
                            [{"name": "Authorization", "value": "Bearer %s"}]""".formatted(sentinel)), "$.id");
            assertThat(patch(ana, id, """
                            {"headers": [{"name": "X-Api-Key", "value": "%s-2"}]}""".formatted(sentinel))).hasStatus(200);
            assertThat(get(ana, id)).hasStatus(200);
            assertThat(create(ana, project, "Rejected", """
                            [{"name": "X-Api-Key", "value": "%s\\r\\nX-Injected: yes"}]""".formatted(sentinel)))
                    .hasStatus(400);
        } finally {
            loggers.forEach(logger -> loggingSystem.setLogLevel(logger, null));
        }

        assertThat(output).contains("Payments API").doesNotContain(sentinel);
    }

    /** The id of the monitor is in the associated data: another monitor's ciphertext does not open. */
    @Test
    void aCiphertextCopiedToAnotherMonitorDoesNotDecrypt() {
        Caller ana = signedIn();
        String project = projectOf(ana);
        String sentinel = sentinel();
        String source = read(create(ana, project, "Source", """
                        [{"name": "X-Api-Key", "value": "%s"}]""".formatted(sentinel)), "$.id");
        String target = read(create(ana, project, "Target", "[]"), "$.id");

        jdbc.update(
                "UPDATE monitors SET request_headers = (SELECT request_headers FROM monitors WHERE id = ?) WHERE id = ?",
                UUID.fromString(source),
                UUID.fromString(target));

        MvcTestResult stolen = get(ana, target);
        assertThat(stolen).hasStatus(500).bodyJson().extractingPath("$.code").isEqualTo("internal-error");
        assertThat(content(stolen)).doesNotContain(sentinel).doesNotContain("X-Api-Key");
        assertThat(get(ana, source)).hasStatus(200);
    }

    @Test
    void aPatchReplacesTheWholeListAndOnlyWhenItChanges() {
        Caller ana = signedIn();
        String id = read(create(ana, projectOf(ana), "Payments API", """
                        [{"name": "Authorization", "value": "Bearer one"}]"""), "$.id");

        MvcTestResult replaced = patch(ana, id, """
                {"headers": [{"name": "X-Api-Key", "value": "two"}, {"name": "Accept", "value": "application/json"}]}""");
        assertThat(replaced).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"1\"");
        assertThat(names(replaced)).containsExactly("X-Api-Key", "Accept");
        byte[] stored = storedHeaders(id);

        // The same list again, or no list: nothing to encrypt again, the version stays
        MvcTestResult same = patch(ana, id, """
                {"headers": [{"name": "X-Api-Key", "value": "two"}, {"name": "Accept", "value": "application/json"}]}""");
        assertThat(same).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"1\"");
        assertThat(patch(ana, id, "{\"name\": \"Payments\"}"))
                .bodyJson()
                .extractingPath("$.headers[*].name")
                .isEqualTo(List.of("X-Api-Key", "Accept"));
        assertThat(storedHeaders(id)).isEqualTo(stored);

        MvcTestResult removed = patch(ana, id, "{\"headers\": []}");
        assertThat(removed)
                .hasStatus(200)
                .bodyJson()
                .extractingPath("$.headers")
                .isEqualTo(List.of());
        assertThat(storedHeaders(id)).isNull();

        assertThat(patch(ana, id, "{\"headers\": null}"))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("malformed-request");
    }

    /** Cases 20 and 21 of docs/security/ssrf-protection.md, and the limits of layer 4, on creating and on changing. */
    @Test
    void rejectsTheHeadersOfLayerFourOnSaving() {
        Caller ana = signedIn();
        String project = projectOf(ana);
        String id = read(create(ana, project, "Payments API", "[]"), "$.id");
        String eleven = IntStream.range(0, 11)
                .mapToObj(i -> "{\"name\": \"X-Header-%d\", \"value\": \"x\"}".formatted(i))
                .collect(Collectors.joining(", ", "[", "]"));

        Map<String, String> rejected = Map.of(
                "[{\"name\": \"Metadata-Flavor\", \"value\": \"Google\"}]",
                "headers[0].name",
                "[{\"name\": \"Authorization\", \"value\": \"Bearer Oracle\"}]",
                "headers[0].value",
                "[{\"name\": \"Accept\", \"value\": \"*/*\"}, {\"name\": \"X-Api-Key\", \"value\": \"a\\r\\nHost: evil\"}]",
                "headers[1].value",
                "[{\"name\": \"X-Forwarded-For\", \"value\": \"127.0.0.1\"}]",
                "headers[0].name",
                "[{\"name\": \"Bad Name\", \"value\": \"x\"}]",
                "headers[0].name",
                eleven,
                "headers",
                "[{\"name\": \"X-Api-Key\"}]",
                "headers[0].value",
                "[null]",
                "headers[0]");
        rejected.forEach((headers, field) -> {
            assertThat(create(ana, project, "Rejected", headers))
                    .as(headers)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.errors[0].field")
                    .isEqualTo(field);
            assertThat(patch(ana, id, "{\"headers\": %s}".formatted(headers)))
                    .as(headers)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.errors[0].field")
                    .isEqualTo(field);
        });

        assertThat(list(ana, project))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(1);
        assertThat(get(ana, id)).bodyJson().extractingPath("$.headers").isEqualTo(List.of());
    }

    /** The example of the README, now with the headers of each API. */
    @Test
    void theCharityLinkExampleCanBeCreatedWithHeaders() {
        Caller ana = signedIn();
        String production = projectOf(ana);

        for (String name : List.of("Authentication API", "Donations API", "Payments API", "Notifications API")) {
            assertThat(create(ana, production, name, """
                            [{"name": "Authorization", "value": "Bearer %s"}, {"name": "Accept", "value": "application/json"}]""".formatted(sentinel())))
                    .hasStatus(201)
                    .bodyJson()
                    .extractingPath("$.headers[*].name")
                    .isEqualTo(List.of("Authorization", "Accept"));
        }

        assertThat(list(ana, production))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(4);
    }

    @Test
    void isDocumentedAsWriteOnly() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.components.schemas.HeaderInput.properties.value.writeOnly")
                .isEqualTo(true);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.components.schemas.UpdateMonitorRequest.properties.headers.type")
                .isEqualTo("array");
    }

    /** A signed-in user: the access token and the id. */
    private record Caller(String token, String id) {}

    private static String sentinel() {
        return "sentinel-" + UUID.randomUUID();
    }

    private static Map<String, Object> headerWithoutValue(String name, boolean hasValue) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("name", name);
        header.put("value", null);
        header.put("hasValue", hasValue);
        return header;
    }

    private byte @Nullable [] storedHeaders(String monitorId) {
        return jdbc.queryForObject(
                "SELECT request_headers FROM monitors WHERE id = ?", byte[].class, UUID.fromString(monitorId));
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

    private String projectOf(Caller caller) {
        MvcTestResult organization = as(
                        caller, json(mvc.post(), "/api/v1/organizations", "{\"name\": \"CharityLink\"}"))
                .exchange();
        assertThat(organization).hasStatus(201);
        MvcTestResult project = as(
                        caller,
                        json(
                                mvc.post(),
                                "/api/v1/organizations/" + read(organization, "$.id") + "/projects",
                                "{\"name\": \"Production\"}"))
                .exchange();
        assertThat(project).hasStatus(201);
        return read(project, "$.id");
    }

    private MvcTestResult create(Caller caller, String projectId, String name, String headers) {
        return as(
                        caller,
                        json(
                                mvc.post(),
                                "/api/v1/projects/" + projectId + "/monitors",
                                "{\"name\": \"%s\", \"url\": \"%s\", \"headers\": %s}"
                                        .formatted(name, HEALTH, headers)))
                .exchange();
    }

    private MvcTestResult get(Caller caller, String id) {
        return as(caller, mvc.get().uri("/api/v1/monitors/" + id)).exchange();
    }

    private MvcTestResult list(Caller caller, String projectId) {
        return as(caller, mvc.get().uri("/api/v1/projects/" + projectId + "/monitors"))
                .exchange();
    }

    private MvcTestResult patch(Caller caller, String id, String body) {
        return as(caller, json(mvc.patch(), "/api/v1/monitors/" + id, body)).exchange();
    }

    private static MockMvcRequestBuilder as(Caller caller, MockMvcRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token());
    }

    private static MockMvcRequestBuilder json(MockMvcRequestBuilder request, String uri, String body) {
        return request.uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static List<String> names(MvcTestResult result) {
        return JsonPath.read(content(result), "$.headers[*].name");
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
