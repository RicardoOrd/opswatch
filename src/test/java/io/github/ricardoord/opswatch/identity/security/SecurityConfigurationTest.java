package io.github.ricardoord.opswatch.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import io.github.ricardoord.opswatch.shared.error.ProblemDetailsErrorController;
import io.github.ricardoord.opswatch.shared.error.ProblemDetailsSecurityHandlers;
import io.github.ricardoord.opswatch.shared.error.ProblemFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// Only the error controller plus the probe below: the real controllers would need their services
@WebMvcTest(controllers = ProblemDetailsErrorController.class)
@Import({
    SecurityConfiguration.class,
    ProblemFactory.class,
    ProblemDetailsSecurityHandlers.class,
    SecurityConfigurationTest.ProbeController.class
})
@TestPropertySource(properties = "opswatch.security.cors.allowed-origins=https://app.example.com")
class SecurityConfigurationTest {

    private static final String ALLOWED_ORIGIN = "https://app.example.com";

    @Autowired
    private MockMvcTester mvc;

    // Real token validation is covered by JwtSecurityIT; here only its outcome matters
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void protectedEndpointsRequireAuthenticationWithProblemDetails() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe").exchange();

        assertThat(result).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("unauthenticated");
        assertThat(result).headers().hasValue(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        assertThat(result).headers().containsHeader("X-Request-Id");
    }

    @Test
    void rejectedBearerTokensGetProblemDetailsAndAnInvalidTokenChallenge() throws Exception {
        given(jwtDecoder.decode(anyString())).willThrow(new BadJwtException("Signature mismatch"));

        MvcTestResult result = mvc.get()
                .uri("/api/v1/probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid-token")
                .exchange();

        assertThat(result).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("unauthenticated");
        assertThat(result).headers().hasValue(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        // Why the token was rejected stays in the server
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Signature");
    }

    @Test
    void unknownRoutesAreDeniedBeforeAnythingElse() {
        // Deny by default: a route nobody declared is closed, not open
        assertThat(mvc.get().uri("/api/v1/not-declared").exchange()).hasStatus(401);
        assertThat(mvc.get().uri("/anything-else").exchange()).hasStatus(401);
    }

    @Test
    @WithMockUser
    void authenticatedRequestsReachTheController() {
        assertThat(mvc.get().uri("/api/v1/probe").exchange()).hasStatus(200);
    }

    @Test
    void authEndpointsArePublicAndNeedNoCsrfToken() {
        assertThat(mvc.post().uri("/api/v1/auth/probe").exchange()).hasStatus(200);
    }

    @Test
    void apiResponsesCarrySecurityHeaders() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe").exchange();

        assertThat(result).headers().hasValue("Content-Security-Policy", SecurityConfiguration.API_CSP);
        assertThat(result).headers().hasValue("X-Content-Type-Options", "nosniff");
        assertThat(result).headers().hasValue("X-Frame-Options", "DENY");
        assertThat(result).headers().hasValue("Referrer-Policy", "no-referrer");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void neitherFormLoginNorHttpBasicExist() {
        assertThat(mvc.get().uri("/login").exchange()).hasStatus(401);
        assertThat(mvc.get()
                        .uri("/api/v1/probe")
                        .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA==")
                        .exchange())
                .hasStatus(401);
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() {
        MvcTestResult allowed = preflight(ALLOWED_ORIGIN);
        assertThat(allowed).hasStatus(200);
        assertThat(allowed).headers().hasValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN);

        MvcTestResult rejected = preflight("https://evil.example");
        assertThat(rejected).hasStatus(403);
        assertThat(rejected).headers().doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void apiDocsAreNotBlockedAndSkipTheStrictCsp() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(401);
        assertThat(result).headers().doesNotContainHeader("Content-Security-Policy");
    }

    private MvcTestResult preflight(String origin) {
        return mvc.options()
                .uri("/api/v1/probe")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange();
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/probe")
        String probe() {
            return "ok";
        }

        @PostMapping("/api/v1/auth/probe")
        String publicProbe() {
            return "ok";
        }
    }
}
