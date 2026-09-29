package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.application.AuthenticationService;
import io.github.ricardoord.opswatch.identity.application.InvalidRefreshTokenException;
import io.github.ricardoord.opswatch.identity.application.RegistrationService;
import io.github.ricardoord.opswatch.identity.application.SessionTokens;
import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.identity.security.AuthRateLimiter;
import io.github.ricardoord.opswatch.identity.security.TrustedOrigins;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

/** Public authentication endpoints. Spring Security lets {@code /api/v1/auth/**} through without a token. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
class AuthController {

    /** The account of the caller once signed in: the only URL through which a user reads their own account. */
    static final URI CURRENT_USER = URI.create("/api/v1/me");

    private static final String RETRY_AFTER = "Seconds until the next attempt is allowed";

    private final RegistrationService registration;
    private final AuthenticationService authentication;
    private final RefreshTokenCookies cookies;
    private final TrustedOrigins trustedOrigins;
    private final AuthRateLimiter rateLimiter;

    AuthController(
            RegistrationService registration,
            AuthenticationService authentication,
            RefreshTokenCookies cookies,
            TrustedOrigins trustedOrigins,
            AuthRateLimiter rateLimiter) {
        this.registration = registration;
        this.authentication = authentication;
        this.cookies = cookies;
        this.trustedOrigins = trustedOrigins;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account")
    @ApiResponse(responseCode = "201", description = "Account created. It does not sign the user in")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid fields, malformed JSON or unknown properties",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The email is already registered, whatever its case",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "429",
            description = "More than 5 registrations in an hour from the same address",
            headers =
                    @Header(
                            name = HttpHeaders.RETRY_AFTER,
                            description = RETRY_AFTER,
                            schema = @Schema(type = "integer")),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<UserResponse> register(
            @Valid @RequestBody RegisterUserRequest request, HttpServletRequest httpRequest) {
        rateLimiter.checkRegistration(httpRequest.getRemoteAddr());
        // Bean Validation has already rejected null fields
        User user = registration.register(
                Objects.requireNonNull(request.email()),
                Objects.requireNonNull(request.displayName()),
                Objects.requireNonNull(request.password()));
        return ResponseEntity.created(CURRENT_USER).body(UserResponse.from(user));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with email and password")
    @ApiResponse(
            responseCode = "200",
            description = "Access token, valid for 15 minutes, and the refresh token in an HttpOnly cookie")
    @ApiResponse(
            responseCode = "400",
            description = "Missing fields or malformed JSON",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Wrong credentials. The same answer whether the email exists or not",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "429",
            description = "More than 10 attempts in a minute from the same address, or more than 5 against the same "
                    + "email from any address. The account is never locked",
            headers =
                    @Header(
                            name = HttpHeaders.RETRY_AFTER,
                            description = RETRY_AFTER,
                            schema = @Schema(type = "integer")),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<AccessTokenResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String email = Objects.requireNonNull(request.email());
        rateLimiter.checkLogin(httpRequest.getRemoteAddr(), email);
        SessionTokens session =
                authentication.login(email, Objects.requireNonNull(request.password()), httpRequest.getRemoteAddr());
        return withSession(session);
    }

    /**
     * Only JSON, so a cross-origin form cannot reach it without a CORS preflight (T-09). The body is ignored: the
     * token travels in the cookie.
     */
    @PostMapping(path = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get a new access token with the refresh token cookie",
            description = "Rotates the refresh token: the cookie is replaced and the previous token stops working. "
                    + "Presenting a spent token again ends the whole session. Send Content-Type: application/json "
                    + "and an Origin header; the body is ignored")
    @Parameter(in = ParameterIn.COOKIE, name = "opswatch_refresh", description = "The refresh token")
    @ApiResponse(responseCode = "200", description = "New access token and a new refresh token cookie")
    @ApiResponse(
            responseCode = "401",
            description = "Missing, unknown, expired, revoked or reused refresh token, or a disabled account",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Missing Origin header, or an origin that is not allowed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "429",
            description = "More than 30 refreshes in a minute from the same address",
            headers =
                    @Header(
                            name = HttpHeaders.RETRY_AFTER,
                            description = RETRY_AFTER,
                            schema = @Schema(type = "integer")),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<AccessTokenResponse> refresh(HttpServletRequest httpRequest) {
        rateLimiter.checkRefresh(httpRequest.getRemoteAddr());
        requireTrustedOrigin(httpRequest);
        String refreshToken = refreshToken(httpRequest);
        if (refreshToken == null) {
            throw new InvalidRefreshTokenException();
        }
        return withSession(authentication.refresh(refreshToken, httpRequest.getRemoteAddr()));
    }

    /** Only JSON, like {@code refresh}. Succeeds without a cookie too: there is no session to end. */
    @PostMapping(path = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Sign out: end the session of the refresh token cookie",
            description = "Send Content-Type: application/json and an Origin header; the body is ignored")
    @Parameter(in = ParameterIn.COOKIE, name = "opswatch_refresh", description = "The refresh token")
    @ApiResponse(responseCode = "204", description = "Session ended and cookie deleted")
    @ApiResponse(
            responseCode = "403",
            description = "Missing Origin header, or an origin that is not allowed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        requireTrustedOrigin(httpRequest);
        String refreshToken = refreshToken(httpRequest);
        if (refreshToken != null) {
            authentication.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.cleared().toString())
                .build();
    }

    private ResponseEntity<AccessTokenResponse> withSession(SessionTokens session) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookies.of(session.refreshToken()).toString())
                .body(AccessTokenResponse.from(session.accessToken()));
    }

    private void requireTrustedOrigin(HttpServletRequest request) {
        if (!trustedOrigins.allows(request.getHeader(HttpHeaders.ORIGIN))) {
            throw new UntrustedOriginException();
        }
    }

    private @Nullable String refreshToken(HttpServletRequest request) {
        var cookie = WebUtils.getCookie(request, cookies.name());
        return cookie == null || cookie.getValue().isEmpty() ? null : cookie.getValue();
    }
}
