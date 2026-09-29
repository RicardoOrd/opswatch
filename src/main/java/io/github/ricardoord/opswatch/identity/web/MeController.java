package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.application.ProfileService;
import io.github.ricardoord.opswatch.shared.security.CurrentUser;
import io.github.ricardoord.opswatch.shared.web.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
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
class MeController {

    private final ProfileService profiles;

    MeController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(summary = "The account of the signed-in user")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(
            responseCode = "401",
            description = "Missing, invalid or expired access token",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    UserResponse me(CurrentUser user) {
        return UserResponse.from(profiles.get(user.id()));
    }
}
