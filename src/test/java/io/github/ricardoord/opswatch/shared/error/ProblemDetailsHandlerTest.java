package io.github.ricardoord.opswatch.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import jakarta.servlet.RequestDispatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice test of the error format. It uses {@code @WebMvcTest} on purpose: what is tested is serialization and
 * exception translation, not persistence (see docs/testing/testing-strategy.md#api).
 */
@WebMvcTest(controllers = ProblemDetailsErrorController.class)
@Import({ProblemFactory.class, ProblemDetailsSecurityHandlers.class, ProblemDetailsHandlerTest.ThrowingController.class
})
@WithMockUser
class ProblemDetailsHandlerTest {

    private static final String BASE = "/test/errors";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AuthenticationEntryPoint entryPoint;

    @Test
    void validationErrorListsEveryInvalidFieldInEnglish() {
        MvcTestResult result = mvc.post()
                .uri(BASE + "/validated")
                .with(csrf())
                .header("X-Request-Id", "req-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"\", \"size\": 0}")
                .exchange();

        assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).headers().hasValue("X-Request-Id", "req-123");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("validation-error");
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Validation failed");
        assertThat(result).bodyJson().extractingPath("$.requestId").isEqualTo("req-123");
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(BASE + "/validated");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.type")
                .asString()
                .endsWith("api-guidelines.md#validation-error");
        assertThat(result).bodyJson().extractingPath("$.errors").asArray().hasSize(2);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[?(@.field == 'name')].message")
                .asArray()
                .containsExactly("must not be blank");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[?(@.field == 'name')].code")
                .asArray()
                .containsExactly("not-blank");
    }

    @Test
    void malformedJsonIsRejected() {
        assertProblem(postJson("{"), 400, "malformed-request");
    }

    @Test
    void unknownPropertiesAreRejected() {
        // Guards against mass assignment: a field the DTO does not declare is an error, not silently ignored
        assertProblem(postJson("{\"name\": \"a\", \"size\": 1, \"role\": \"OWNER\"}"), 400, "malformed-request");
    }

    @Test
    void invalidPathParameterIsReported() {
        assertProblem(mvc.get().uri(BASE + "/items/not-a-uuid").exchange(), 400, "invalid-parameter");
    }

    @ParameterizedTest
    @CsvSource({
        "not-found,          404, resource-not-found",
        "permission-denied,  403, access-denied",
        "conflict,           409, conflict",
        "business-rule,      409, business-rule-violation",
        "precondition,       412, precondition-failed",
        "quota,              422, quota-exceeded",
        "target-not-allowed, 422, target-not-allowed",
        "optimistic-lock,    409, concurrent-modification",
        "bad-credentials,    401, unauthenticated",
        "access-denied,      403, access-denied"
    })
    void translatesKnownExceptions(String path, int status, String code) {
        assertProblem(mvc.get().uri(BASE + "/" + path).exchange(), status, code);
    }

    @Test
    void authenticationErrorsAdvertiseBearer() {
        MvcTestResult result = mvc.get().uri(BASE + "/bad-credentials").exchange();

        assertThat(result).headers().hasValue("WWW-Authenticate", "Bearer");
    }

    @Test
    void unexpectedErrorsRevealNothingInternal() throws Exception {
        MvcTestResult result = mvc.get().uri(BASE + "/unexpected").exchange();

        assertProblem(result, 500, "internal-error");
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("hunter2")
                .doesNotContain("select")
                .doesNotContain("IllegalStateException")
                .doesNotContain("java.")
                .doesNotContain("\"trace\"")
                .doesNotContain("\"exception\"");
    }

    @Test
    void unknownRouteIsNotFound() {
        assertProblem(mvc.get().uri("/does-not-exist").exchange(), 404, "resource-not-found");
    }

    @Test
    void wrongMethodIsReported() {
        assertProblem(mvc.delete().uri(BASE + "/validated").with(csrf()).exchange(), 405, "method-not-allowed");
    }

    @Test
    void wrongContentTypeIsReported() {
        MvcTestResult result = mvc.post()
                .uri(BASE + "/validated")
                .with(csrf())
                .contentType(MediaType.TEXT_PLAIN)
                .content("name=a")
                .exchange();

        assertProblem(result, 415, "unsupported-media-type");
    }

    @Test
    void securityEntryPointUsesTheSameFormat() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/monitors");
        var response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void errorsOutsideSpringMvcUseProblemDetails() {
        MvcTestResult result = mvc.get()
                .uri("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/monitors")
                .requestAttr("requestId", "from-filter")
                .exchange();

        assertProblem(result, 500, "internal-error");
        assertThat(result).bodyJson().extractingPath("$.requestId").isEqualTo("from-filter");
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/v1/monitors");
    }

    @Test
    void constraintNamesBecomeKebabCase() {
        assertThat(ProblemDetailsHandler.kebab("NotBlank")).isEqualTo("not-blank");
        assertThat(ProblemDetailsHandler.kebab("Min")).isEqualTo("min");
        assertThat(ProblemDetailsHandler.kebab(null)).isEqualTo("invalid");
    }

    private MvcTestResult postJson(String json) {
        return mvc.post()
                .uri(BASE + "/validated")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private static void assertProblem(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status);
    }

    @RestController
    @RequestMapping(BASE)
    static class ThrowingController {

        record Payload(@NotBlank String name, @Min(1) int size) {}

        @PostMapping("/validated")
        void validated(@Valid @RequestBody Payload payload) {}

        @GetMapping("/items/{id}")
        void item(@PathVariable UUID id) {}

        @GetMapping("/not-found")
        void notFound() {
            throw new ResourceNotFoundException("monitor", "0192b3c4");
        }

        @GetMapping("/permission-denied")
        void permissionDenied() {
            throw new PermissionDeniedException("Your role cannot delete monitors");
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException("A project with that name already exists");
        }

        @GetMapping("/business-rule")
        void businessRule() {
            throw new BusinessRuleViolationException("An organization needs at least one owner");
        }

        @GetMapping("/precondition")
        void precondition() {
            throw new PreconditionFailedException("The resource version does not match If-Match");
        }

        @GetMapping("/quota")
        void quota() {
            throw new QuotaExceededException("The organization already has 50 monitors");
        }

        @GetMapping("/target-not-allowed")
        void targetNotAllowed() {
            throw new TargetNotAllowedException("The target resolves to a private network");
        }

        @GetMapping("/optimistic-lock")
        void optimisticLock() {
            throw new OptimisticLockingFailureException("row was updated by another transaction");
        }

        @GetMapping("/bad-credentials")
        void badCredentials() {
            throw new BadCredentialsException("bad");
        }

        @GetMapping("/access-denied")
        void accessDenied() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("select * from users where password = 'hunter2'");
        }
    }
}
