package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.application.ProfileService;
import io.github.ricardoord.opswatch.identity.security.AuthRateLimiter;
import io.github.ricardoord.opswatch.shared.security.CurrentUser;
import io.github.ricardoord.opswatch.shared.web.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own account. It carries no organizations: {@code identity} cannot depend on
 * {@code organization}, and {@code GET /api/v1/organizations} lists them with the user's role.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Current user")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@ApiResponse(
        responseCode = "401",
        description = "Missing, invalid or expired access token",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
class MeController {

    private final ProfileService profiles;
    private final AuthRateLimiter rateLimiter;

    MeController(ProfileService profiles, AuthRateLimiter rateLimiter) {
        this.profiles = profiles;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    @Operation(summary = "The account of the signed-in user")
    @ApiResponse(responseCode = "200", description = "The account")
    UserResponse me(CurrentUser user) {
        return UserResponse.from(profiles.get(user.id()));
    }

    @PatchMapping
    @Operation(
            summary = "Change the display name",
            description = "Only the fields sent change. The email cannot be changed")
    @ApiResponse(responseCode = "200", description = "The account after the change")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid or null displayName, malformed JSON or unknown properties, email included",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    UserResponse update(CurrentUser user, @Valid @RequestBody UpdateProfileRequest request) {
        String displayName = request.displayName();
        return UserResponse.from(
                displayName == null ? profiles.get(user.id()) : profiles.rename(user.id(), displayName));
    }

    @PostMapping("/password")
    @Operation(
            summary = "Change the password",
            description = "Needs the current password. Ends every session of the user: their refresh tokens stop "
                    + "working, and the access tokens already issued last until they expire")
    @ApiResponse(responseCode = "204", description = "Password changed and every session ended")
    @ApiResponse(
            responseCode = "400",
            description = "A wrong currentPassword (listed in errors like any invalid field), a newPassword against "
                    + "the password policy, or malformed JSON",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "429",
            description = "More than 5 attempts in 15 minutes for the same user, from any address",
            headers =
                    @Header(
                            name = HttpHeaders.RETRY_AFTER,
                            description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<Void> changePassword(
            CurrentUser user, @Valid @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        rateLimiter.checkPasswordChange(user.id(), httpRequest.getRemoteAddr());
        // Bean Validation has already rejected null fields
        profiles.changePassword(
                user.id(),
                Objects.requireNonNull(request.currentPassword()),
                Objects.requireNonNull(request.newPassword()),
                httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
