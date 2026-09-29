package io.github.ricardoord.opswatch.identity.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code POST /api/v1/auth/login} and {@code GET /api/v1/me} through the real security chain, against PostgreSQL. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LoginApiIT {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aLoginGivesAnAccessTokenThatOpensTheAccount() throws Exception {
        String email = registered("Ana");

        MvcTestResult login = login(email, PASSWORD);

        assertThat(login).hasStatus(200).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(login).bodyJson().extractingPath("$.tokenType").isEqualTo("Bearer");
        assertThat(login).bodyJson().extractingPath("$.expiresIn").isEqualTo(900);
        // RFC 6749: a response with a token is never cached
        assertThat(login.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        MvcTestResult me = me(accessToken(login));

        assertThat(me).hasStatus(200);
        assertThat(me).bodyJson().extractingPath("$.email").isEqualTo(email);
        assertThat(me).bodyJson().extractingPath("$.displayName").isEqualTo("Ana");
        assertThat(me).bodyJson().extractingPath("$.id").asString().isNotBlank();
        assertThat(me).bodyJson().extractingPath("$.createdAt").asString().endsWith("Z");
        assertThat(me).bodyJson().doesNotHavePath("$.passwordHash");
    }

    @Test
    void ignoresTheCaseAndSurroundingSpacesOfTheEmail() {
        String email = registered("Ana");

        assertThat(login("  " + email.toUpperCase(Locale.ROOT) + " ", PASSWORD)).hasStatus(200);
    }

    @Test
    void answersTheSameForAWrongPasswordAnUnknownEmailAndADisabledAccount() throws Exception {
        String email = registered("Ana");
        String disabled = registered("Bea");
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE email = ?", disabled);

        List<MvcTestResult> results =
                List.of(login(email, "wrong password"), login(uniqueEmail(), PASSWORD), login(disabled, PASSWORD));

        for (MvcTestResult result : results) {
            assertThat(result).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("invalid-credentials");
            assertThat(result).headers().hasValue(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        assertThat(withoutRequestId(results.get(1)))
                .isEqualTo(withoutRequestId(results.get(0)))
                .isEqualTo(withoutRequestId(results.get(2)));
    }

    @Test
    void takesAboutAsLongForAnUnknownEmailAsForAWrongPassword() {
        String email = registered("Ana");
        String unknown = uniqueEmail();
        List<Long> wrongPassword = new ArrayList<>();
        List<Long> unknownEmail = new ArrayList<>();
        login(email, "warm-up");

        for (int i = 0; i < 25; i++) {
            wrongPassword.add(timeToFail(email));
            unknownEmail.add(timeToFail(unknown));
        }

        long difference = Math.abs(median(wrongPassword) - median(unknownEmail));
        assertThat(Duration.ofNanos(difference)).isLessThan(Duration.ofMillis(50));
    }

    @Test
    void rejectsAPasswordNoAccountCanHaveWithTheSame401() {
        String email = registered("Ana");

        MvcTestResult result = login(email, "é".repeat(36) + "a");

        assertThat(result).hasStatus(401);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("invalid-credentials");
    }

    @Test
    void listsMissingFieldsAsAValidationError() {
        MvcTestResult result = post("{\"email\": \" \"}");

        assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("validation-error");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .contains("email", "password");
    }

    @Test
    void theAccountNeedsAnAccessToken() {
        MvcTestResult result = mvc.get().uri("/api/v1/me").exchange();

        assertThat(result).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("unauthenticated");
    }

    @Test
    void logsTheFailureWithoutTheEmailOrThePassword(CapturedOutput output) {
        String email = registered("Ana");

        assertThat(login(email, "wrong password")).hasStatus(401);

        assertThat(output)
                .contains("Login failed: wrong-password")
                .doesNotContain(email)
                .doesNotContain("wrong password");
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result).hasStatus(200);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/auth/login'].post.responses")
                .asMap()
                .containsKeys("200", "400", "401");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/me'].get.responses")
                .asMap()
                .containsKeys("200", "401");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/me'].get.security[0]")
                .asMap()
                .containsKey("bearer-jwt");
        // The current user comes from the token, never from the request
        assertThat(result).bodyJson().doesNotHavePath("$.paths['/api/v1/me'].get.parameters");
    }

    private String registered(String displayName) {
        String email = uniqueEmail();
        String body = """
                {"email": "%s", "displayName": "%s", "password": "%s"}
                """.formatted(email, displayName, PASSWORD);
        MvcTestResult result = mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(result).hasStatus(201);
        return email;
    }

    private MvcTestResult login(String email, String password) {
        return post("""
                {"email": "%s", "password": "%s"}
                """.formatted(email, password));
    }

    private MvcTestResult post(String body) {
        return mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult me(String accessToken) {
        return mvc.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private long timeToFail(String email) {
        long start = System.nanoTime();
        MvcTestResult result = login(email, "wrong password");
        long elapsed = System.nanoTime() - start;
        assertThat(result).hasStatus(401);
        return elapsed;
    }

    private static long median(List<Long> values) {
        return values.stream().sorted().toList().get(values.size() / 2);
    }

    private static String accessToken(MvcTestResult login) throws Exception {
        return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String withoutRequestId(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString().replaceAll("\"requestId\":\"[^\"]*\"", "");
    }
}
