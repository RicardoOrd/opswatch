package io.github.ricardoord.opswatch.identity.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import java.util.Locale;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code POST /api/v1/auth/register} through the real security chain, against PostgreSQL. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RegistrationApiIT {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoggingSystem loggingSystem;

    @Test
    void createsTheAccountAndReturnsItWithoutTheHash() throws Exception {
        String email = uniqueEmail();

        MvcTestResult result = register(email, "Ana", PASSWORD);

        assertThat(result).hasStatus(201);
        assertThat(result).headers().hasValue(HttpHeaders.LOCATION, "/api/v1/me");
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
        assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Ana");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.id")
                .asString()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(result).bodyJson().extractingPath("$.createdAt").asString().endsWith("Z");
        assertThat(result).bodyJson().doesNotHavePath("$.passwordHash");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("bcrypt");
    }

    @Test
    void storesTheHashWithItsAlgorithmPrefixAndNeverThePassword() {
        String email = uniqueEmail();

        assertThat(register(email, "Ana", PASSWORD)).hasStatus(201);

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
    }

    @Test
    void storesTheEmailNormalized() {
        String email = uniqueEmail();

        MvcTestResult result = register("  " + email.toUpperCase(Locale.ROOT) + " ", "Ana", PASSWORD);

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
    }

    @Test
    void rejectsAnEmailAlreadyRegisteredWhateverItsCase() {
        String email = uniqueEmail();
        assertThat(register(email, "Ana", PASSWORD)).hasStatus(201);

        MvcTestResult result = register(email.toUpperCase(Locale.ROOT), "Otra Ana", PASSWORD);

        assertThat(result).hasStatus(409).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("conflict");
    }

    @Test
    void listsEveryInvalidFieldAtOnce() {
        MvcTestResult result = register("not-an-email", " ", "short");

        assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("validation-error");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .contains("email", "displayName", "password");
    }

    @Test
    void rejectsAPasswordLongerThan72Bytes() {
        String seventyThreeBytes = "é".repeat(36) + "a";

        MvcTestResult result = register(uniqueEmail(), "Ana", seventyThreeBytes);

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("password");
        assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo("password-policy");
    }

    @Test
    void acceptsAPasswordOfExactly72Bytes() {
        assertThat(register(uniqueEmail(), "Ana", "é".repeat(36))).hasStatus(201);
    }

    @Test
    void rejectsDisplayNamesWithControlCharacters() {
        MvcTestResult result = register(uniqueEmail(), "Ana\u202Egnp.exe", PASSWORD);

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("displayName");
    }

    @Test
    void rejectsUnknownPropertiesInsteadOfIgnoringThem() {
        String body = """
                {"email": "%s", "displayName": "Ana", "password": "%s", "status": "DISABLED"}
                """.formatted(uniqueEmail(), PASSWORD);

        MvcTestResult result = post(body);

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("malformed-request");
    }

    @Test
    void logsNeitherThePasswordNorItsHash(CapturedOutput output) {
        String logger = "io.github.ricardoord.opswatch";
        loggingSystem.setLogLevel(logger, LogLevel.DEBUG);
        try {
            String email = uniqueEmail();
            assertThat(register(email, "Ana", PASSWORD)).hasStatus(201);
            assertThat(register(email, "Ana", PASSWORD)).hasStatus(409);
            assertThat(register(uniqueEmail(), "Ana", "short")).hasStatus(400);
        } finally {
            loggingSystem.setLogLevel(logger, null);
        }

        assertThat(output).doesNotContain(PASSWORD).doesNotContain("{bcrypt}");
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result).hasStatus(200);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/auth/register'].post.responses")
                .asMap()
                .containsKeys("201", "400", "409");
    }

    private MvcTestResult register(String email, String displayName, String password) {
        String body = """
                {"email": "%s", "displayName": "%s", "password": "%s"}
                """.formatted(email, displayName, password);
        return post(body);
    }

    private MvcTestResult post(String body) {
        return mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }
}
