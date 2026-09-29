package io.github.ricardoord.opswatch.identity.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestJwtKeys;
import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code POST /api/v1/me/password} through the real security chain, against PostgreSQL and the real sessions. The
 * limit of 5 attempts in 15 minutes is the real one: it goes per user, and each test has its own.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PasswordChangeApiIT {

    private static final String PASSWORD = "correct horse battery";
    private static final String NEW_PASSWORD = "another long passphrase";
    private static final String COOKIE = "opswatch_refresh";
    private static final Pattern COOKIE_VALUE = Pattern.compile(COOKIE + "=([^;]*)");

    @Autowired
    private MockMvcTester mvc;

    @Test
    void changesThePasswordAndEndsEverySessionOfTheUserOnly() throws Exception {
        String email = registered();
        MvcTestResult firstSession = login(email, PASSWORD);
        MvcTestResult secondSession = login(email, PASSWORD);
        MvcTestResult someoneElse = login(registered(), PASSWORD);

        MvcTestResult result = changePassword(accessToken(firstSession), PASSWORD, NEW_PASSWORD);

        assertThat(result).hasStatus(204);
        assertThat(refresh(refreshToken(firstSession))).hasStatus(401);
        assertThat(refresh(refreshToken(secondSession))).hasStatus(401);
        assertThat(refresh(refreshToken(someoneElse))).hasStatus(200);
        assertThat(login(email, PASSWORD)).hasStatus(401);
        assertThat(login(email, NEW_PASSWORD)).hasStatus(200);
        // The access tokens already issued last until they expire (ADR-004)
        assertThat(me(accessToken(firstSession))).hasStatus(200);
    }

    @Test
    void aWrongCurrentPasswordIsAValidationErrorOnThatFieldAndChangesNothing() throws Exception {
        String email = registered();
        MvcTestResult session = login(email, PASSWORD);

        MvcTestResult result = changePassword(accessToken(session), "wrong password!", NEW_PASSWORD);

        // 400 and not 401: the access token is fine, and a 401 would make the client refresh it
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("validation-error");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("currentPassword");
        assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo("incorrect-password");
        assertThat(refresh(refreshToken(session))).hasStatus(200);
        assertThat(login(email, PASSWORD)).hasStatus(200);
    }

    @Test
    void theNewPasswordFollowsThePasswordPolicy() throws Exception {
        String email = registered();
        MvcTestResult session = login(email, PASSWORD);

        MvcTestResult result = changePassword(accessToken(session), PASSWORD, "too short");

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("newPassword");
        assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo("password-policy");
        assertThat(login(email, PASSWORD)).hasStatus(200);
    }

    @Test
    void theSixthAttemptInFifteenMinutesIsRejectedEvenWithTheRightPassword() throws Exception {
        String email = registered();
        String token = accessToken(login(email, PASSWORD));
        for (int i = 0; i < 5; i++) {
            assertThat(changePassword(token, "wrong password " + i, NEW_PASSWORD))
                    .hasStatus(400);
        }

        MvcTestResult result = changePassword(token, PASSWORD, NEW_PASSWORD);

        assertThat(result).hasStatus(429);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("rate-limited");
        // A token every 3 minutes; the real clock may have refilled a fraction of it
        assertThat(Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)))
                .isBetween(170L, 180L);
        assertThat(login(email, PASSWORD)).hasStatus(200);
    }

    @Test
    void logsTheChangeWithoutThePasswords(CapturedOutput output) throws Exception {
        String token = accessToken(login(registered(), PASSWORD));

        changePassword(token, "wrong password!", NEW_PASSWORD);
        changePassword(token, PASSWORD, NEW_PASSWORD);

        assertThat(output)
                .contains("Password change failed")
                .contains("Password changed")
                .doesNotContain(PASSWORD)
                .doesNotContain("wrong password!")
                .doesNotContain(NEW_PASSWORD);
    }

    @Test
    void needsAnAccessToken() {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(PASSWORD, NEW_PASSWORD))
                .exchange();

        assertThat(result).hasStatus(401);
    }

    private String registered() {
        String email = uniqueEmail();
        MvcTestResult result = mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "displayName": "Ana", "password": "%s"}
                        """.formatted(email, PASSWORD))
                .exchange();
        assertThat(result).hasStatus(201);
        return email;
    }

    private MvcTestResult login(String email, String password) {
        return mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password))
                .exchange();
    }

    private MvcTestResult changePassword(String accessToken, String currentPassword, String newPassword) {
        return mvc.post()
                .uri("/api/v1/me/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(currentPassword, newPassword))
                .exchange();
    }

    private static String body(String currentPassword, String newPassword) {
        return """
                {"currentPassword": "%s", "newPassword": "%s"}
                """.formatted(currentPassword, newPassword);
    }

    private MvcTestResult refresh(String refreshToken) {
        return mvc.post()
                .uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, TestJwtKeys.ISSUER)
                .cookie(new Cookie(COOKIE, refreshToken))
                .exchange();
    }

    private MvcTestResult me(String accessToken) {
        return mvc.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private static String refreshToken(MvcTestResult login) {
        assertThat(login).hasStatus(200);
        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        Matcher matcher = COOKIE_VALUE.matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String accessToken(MvcTestResult login) throws Exception {
        assertThat(login).hasStatus(200);
        return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
    }
}
