package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.application.AuthenticationService;
import io.github.ricardoord.opswatch.identity.application.RegistrationService;
import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.identity.security.AccessToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public authentication endpoints. Spring Security lets {@code /api/v1/auth/**} through without a token. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
class AuthController {

    /** The account of the caller once signed in: the only URL through which a user reads their own account. */
    static final URI CURRENT_USER = URI.create("/api/v1/me");

    private final RegistrationService registration;
    private final AuthenticationService authentication;

    AuthController(RegistrationService registration, AuthenticationService authentication) {
        this.registration = registration;
        this.authentication = authentication;
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
    ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterUserRequest request) {
        // Bean Validation has already rejected null fields
        User user = registration.register(
                Objects.requireNonNull(request.email()),
                Objects.requireNonNull(request.displayName()),
                Objects.requireNonNull(request.password()));
        return ResponseEntity.created(CURRENT_USER).body(UserResponse.from(user));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with email and password")
    @ApiResponse(responseCode = "200", description = "Access token, valid for 15 minutes")
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
    AccessTokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        AccessToken token = authentication.login(
                Objects.requireNonNull(request.email()),
                Objects.requireNonNull(request.password()),
                httpRequest.getRemoteAddr());
        return AccessTokenResponse.from(token);
    }
}
