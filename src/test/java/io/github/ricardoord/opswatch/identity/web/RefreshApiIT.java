package io.github.ricardoord.opswatch.identity.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestJwtKeys;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
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

/**
 * {@code POST /api/v1/auth/refresh} and {@code /logout} through the real security chain, against PostgreSQL. The
 * race between two refreshes with the same token is in {@code RefreshTokenIT}.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RefreshApiIT {

    private static final String PASSWORD = "correct horse battery";
    private static final String COOKIE = "opswatch_refresh";
    private static final Pattern COOKIE_VALUE = Pattern.compile(COOKIE + "=([^;]*)");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theLoginSetsARefreshCookieOutOfReachOfScriptsAndOtherSites() {
        MvcTestResult login = login(registered());

        assertThat(login).hasStatus(200);
        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith(COOKIE + "=")
                .contains("; Path=/api/v1/auth")
                .contains("; Max-Age=1209600")
                .contains("; Secure")
                .contains("; HttpOnly")
                .contains("; SameSite=Strict");
        assertThat(refreshToken(login)).hasSize(43); // 32 bytes in Base64URL without padding
    }

    @Test
    void aRefreshGivesNewTokensAndSpendsThePreviousOne() throws Exception {
        String first = refreshToken(login(registered()));

        MvcTestResult refresh = refresh(first);

        assertThat(refresh).hasStatus(200).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(refresh).bodyJson().extractingPath("$.tokenType").isEqualTo("Bearer");
        assertThat(refresh).bodyJson().extractingPath("$.expiresIn").isEqualTo(900);
        assertThat(refresh.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        String second = refreshToken(refresh);
        assertThat(second).isNotEqualTo(first);
        assertThat(me(accessToken(refresh))).hasStatus(200);

        assertThat(refresh(second)).hasStatus(200);
    }

    @Test
    void reusingASpentTokenEndsTheWholeSession(CapturedOutput output) {
        String first = refreshToken(login(registered()));
        String second = refreshToken(refresh(first));

        MvcTestResult reuse = refresh(first);

        assertThat(reuse).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(reuse).bodyJson().extractingPath("$.code").isEqualTo("unauthenticated");
        assertThat(reuse).headers().hasValue(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        // The legitimate holder of the newest token is signed out too: nobody knows which copy is the attacker's
        assertThat(refresh(second)).hasStatus(401);
        assertThat(reason(first)).isEqualTo("ROTATED");
        assertThat(reason(second)).isEqualTo("REUSE_DETECTED");
        assertThat(output)
                .contains("Refresh token reuse detected")
                .doesNotContain(first)
                .doesNotContain(second);
    }

    @Test
    void aLogoutDeletesTheCookieAndEndsTheSession() {
        String first = refreshToken(login(registered()));
        String second = refreshToken(refresh(first));

        MvcTestResult logout = logout(second);

        assertThat(logout).hasStatus(204);
        assertThat(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(COOKIE + "=;")
                .contains("; Path=/api/v1/auth")
                .contains("; Max-Age=0")
                .contains("; Secure")
                .contains("; HttpOnly")
                .contains("; SameSite=Strict");
        assertThat(refresh(second)).hasStatus(401);
        assertThat(reason(second)).isEqualTo("LOGOUT");
    }

    @Test
    void aLogoutWithoutASessionStillSucceeds() {
        assertThat(logout(null)).hasStatus(204);
        assertThat(logout("unknown-token")).hasStatus(204);
    }

    @Test
    void aDisabledAccountCannotRefreshAndLosesItsSession() {
        String email = registered();
        String token = refreshToken(login(email));
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE email = ?", email);

        MvcTestResult refresh = refresh(token);

        assertThat(refresh).hasStatus(401);
        assertThat(refresh).bodyJson().extractingPath("$.code").isEqualTo("unauthenticated");
        assertThat(reason(token)).isEqualTo("USER_DISABLED");
    }

    @Test
    void aMissingOrUnknownTokenIsUnauthenticated() {
        assertThat(refresh(null)).hasStatus(401);
        assertThat(refresh("")).hasStatus(401);
        assertThat(refresh("unknown-token")).hasStatus(401);
    }

    @Test
    void anotherOriginOrNoneIsForbiddenAndLeavesTheSessionAlone() {
        String token = refreshToken(login(registered()));

        List<MvcTestResult> results = List.of(
                refresh(token, "https://evil.example"),
                refresh(token, null),
                refresh(token, "null"),
                logout(token, "https://evil.example"),
                logout(token, null));

        for (MvcTestResult result : results) {
            assertThat(result).hasStatus(403).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("access-denied");
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        }
        assertThat(refresh(token)).hasStatus(200);
    }

    @Test
    void onlyAcceptsJsonSoAFormFromAnotherSiteCannotPostWithoutAPreflight() {
        String token = refreshToken(login(registered()));

        MvcTestResult form = mvc.post()
                .uri("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, TestJwtKeys.ISSUER)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .cookie(new Cookie(COOKIE, token))
                .exchange();

        assertThat(form).hasStatus(415);
        assertThat(refresh(token)).hasStatus(200);
    }

    @Test
    void storesOnlyTheSha256OfTheToken() throws Exception {
        String token = refreshToken(login(registered()));
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));

        String row = jdbc.queryForObject(
                "SELECT t::text FROM refresh_tokens t WHERE token_hash = ?", String.class, (Object) hash);

        assertThat(row).doesNotContain(token);
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result).hasStatus(200);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/auth/refresh'].post.responses")
                .asMap()
                .containsKeys("200", "401", "403");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/auth/logout'].post.responses")
                .asMap()
                .containsKeys("204", "403");
    }

    private String registered() {
        String email = uniqueEmail();
        String body = """
                {"email": "%s", "displayName": "Ana", "password": "%s"}
                """.formatted(email, PASSWORD);
        MvcTestResult result = mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(result).hasStatus(201);
        return email;
    }

    private MvcTestResult login(String email) {
        return mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD))
                .exchange();
    }

    private MvcTestResult refresh(@Nullable String token) {
        return refresh(token, TestJwtKeys.ISSUER);
    }

    private MvcTestResult refresh(@Nullable String token, @Nullable String origin) {
        return withCookieEndpoint("/api/v1/auth/refresh", token, origin);
    }

    private MvcTestResult logout(@Nullable String token) {
        return logout(token, TestJwtKeys.ISSUER);
    }

    private MvcTestResult logout(@Nullable String token, @Nullable String origin) {
        return withCookieEndpoint("/api/v1/auth/logout", token, origin);
    }

    private MvcTestResult withCookieEndpoint(String uri, @Nullable String token, @Nullable String origin) {
        var request = mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (origin != null) {
            request.header(HttpHeaders.ORIGIN, origin);
        }
        if (token != null) {
            request.cookie(new Cookie(COOKIE, token));
        }
        return request.exchange();
    }

    private MvcTestResult me(String accessToken) {
        return mvc.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private @Nullable String reason(String token) {
        return jdbc.queryForObject(
                "SELECT revocation_reason FROM refresh_tokens WHERE token_hash = sha256(convert_to(?, 'UTF8'))",
                String.class,
                token);
    }

    private static String refreshToken(MvcTestResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        Matcher matcher = COOKIE_VALUE.matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String accessToken(MvcTestResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }
}
