package io.github.ricardoord.opswatch.identity.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code PATCH /api/v1/me} through the real security chain, against PostgreSQL. */
@IntegrationTest
class MeApiIT {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void changesTheDisplayNameWithoutSurroundingSpaces() {
        String email = uniqueEmail();
        String token = signedIn(email);

        MvcTestResult result = patch(token, """
                {"displayName": "  Ana García "}
                """);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Ana García");
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
        assertThat(me(token)).bodyJson().extractingPath("$.displayName").isEqualTo("Ana García");
    }

    @Test
    void anAbsentFieldDoesNotChange() {
        String token = signedIn(uniqueEmail());

        MvcTestResult result = patch(token, "{}");

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Ana");
    }

    @Test
    void theEmailCannotBeChanged() {
        String email = uniqueEmail();
        String token = signedIn(email);

        MvcTestResult result = patch(token, """
                {"displayName": "Bea", "email": "%s"}
                """.formatted(uniqueEmail()));

        // An unknown property, like any other: nothing changes
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("malformed-request");
        assertThat(me(token)).bodyJson().extractingPath("$.email").isEqualTo(email);
        assertThat(me(token)).bodyJson().extractingPath("$.displayName").isEqualTo("Ana");
    }

    @Test
    void aNullDisplayNameIsRejectedInsteadOfIgnored() {
        String token = signedIn(uniqueEmail());

        MvcTestResult result = patch(token, """
                {"displayName": null}
                """);

        assertThat(result).hasStatus(400);
        assertThat(me(token)).bodyJson().extractingPath("$.displayName").isEqualTo("Ana");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "Ana\\nGarcía", "Ana\\u0000"})
    void anInvalidDisplayNameIsAValidationErrorOnThatField(String displayName) {
        String token = signedIn(uniqueEmail());

        MvcTestResult result = patch(token, """
                {"displayName": "%s"}
                """.formatted(displayName));

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("validation-error");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("displayName");
        assertThat(me(token)).bodyJson().extractingPath("$.displayName").isEqualTo("Ana");
    }

    @Test
    void aDisplayNameLongerThan100CharactersIsRejected() {
        String token = signedIn(uniqueEmail());

        assertThat(patch(token, """
                        {"displayName": "%s"}
                        """.formatted("a".repeat(101)))).hasStatus(400);
        assertThat(patch(token, """
                        {"displayName": "%s"}
                        """.formatted("a".repeat(100)))).hasStatus(200);
    }

    @Test
    void theAccountCarriesItsVersionAndAStaleIfMatchChangesNothing() {
        String token = signedIn(uniqueEmail());
        assertThat(me(token)).headers().hasValue(HttpHeaders.ETAG, "\"0\"");

        MvcTestResult renamed = mvc.patch()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"Bea\"}")
                .exchange();
        MvcTestResult stale = mvc.patch()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"Lost update\"}")
                .exchange();

        assertThat(renamed).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"1\"");
        assertThat(stale).hasStatus(412).bodyJson().extractingPath("$.code").isEqualTo("precondition-failed");
        assertThat(me(token)).bodyJson().extractingPath("$.displayName").isEqualTo("Bea");
    }

    @Test
    void needsAnAccessToken() {
        MvcTestResult result = mvc.patch()
                .uri("/api/v1/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"Bea\"}")
                .exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/me'].patch.responses")
                .asMap()
                .containsKeys("200", "400", "401");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/me/password'].post.responses")
                .asMap()
                .containsKeys("204", "400", "401", "429");
    }

    private String signedIn(String email) {
        MvcTestResult registration = mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "displayName": "Ana", "password": "%s"}
                        """.formatted(email, PASSWORD))
                .exchange();
        assertThat(registration).hasStatus(201);
        MvcTestResult login = mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD))
                .exchange();
        assertThat(login).hasStatus(200);
        try {
            return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MvcTestResult patch(String accessToken, String body) {
        return mvc.patch()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
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
}
