package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.application.OrganizationService;
import io.github.ricardoord.opswatch.organization.domain.OrganizationWithRole;
import io.github.ricardoord.opswatch.shared.security.CurrentUser;
import io.github.ricardoord.opswatch.shared.web.ETags;
import io.github.ricardoord.opswatch.shared.web.OpenApiConfiguration;
import io.github.ricardoord.opswatch.shared.web.PageQuery;
import io.github.ricardoord.opswatch.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organizations, the tenants. A user who is not a member gets {@code 404} for any of them, deleted ones included, so
 * the existence of another tenant's organization never shows (docs/security/authorization-model.md).
 */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organizations")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@ApiResponse(
        responseCode = "401",
        description = "Missing, invalid or expired access token",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
class OrganizationController {

    static final Set<String> SORTABLE = Set.of("name", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by("name");

    private final OrganizationService organizations;

    OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @PostMapping
    @Operation(summary = "Create an organization", description = "The caller becomes its OWNER")
    @ApiResponse(responseCode = "201", description = "Created, with its ETag")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid name",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The caller already owns the most organizations allowed (5)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<OrganizationResponse> create(
            CurrentUser user, @Valid @RequestBody CreateOrganizationRequest request) {
        // Bean Validation has already rejected a null name
        OrganizationWithRole created = organizations.create(user.id(), Objects.requireNonNull(request.name()));
        return ResponseEntity.created(URI.create(
                        "/api/v1/organizations/" + created.organization().id()))
                .eTag(ETags.of(created.organization().savedVersion()))
                .body(OrganizationResponse.from(created));
    }

    @GetMapping
    @Operation(
            summary = "The organizations of the caller",
            description = "Only those the caller is a member of, with the caller's role in each")
    @Parameter(
            in = ParameterIn.QUERY,
            name = "page",
            description = "From 0",
            schema = @Schema(type = "integer", defaultValue = "0", minimum = "0"))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "size",
            description = "From 1 to 100",
            schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100"))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "sort",
            description = "name or createdAt, with ,asc or ,desc. Repeatable. By name if absent",
            schema = @Schema(type = "string", example = "name,asc"))
    @ApiResponse(responseCode = "200", description = "A page of organizations")
    @ApiResponse(
            responseCode = "400",
            description = "page, size or sort not allowed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    PageResponse<OrganizationSummaryResponse> list(CurrentUser user, PageQuery page) {
        return PageResponse.from(
                organizations.listOf(user.id(), page.toPageable(SORTABLE, DEFAULT_SORT)),
                OrganizationSummaryResponse::from);
    }

    @GetMapping("/{orgId}")
    @Operation(summary = "An organization", description = "Needs ORGANIZATION_READ")
    @ApiResponse(responseCode = "200", description = "The organization, with its ETag")
    @ApiResponse(
            responseCode = "404",
            description = "It does not exist, it is deleted or the caller is not a member",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<OrganizationResponse> get(CurrentUser user, @PathVariable UUID orgId) {
        return withETag(organizations.get(user.id(), orgId));
    }

    @PatchMapping("/{orgId}")
    @Operation(
            summary = "Rename an organization",
            description = "Needs ORGANIZATION_UPDATE. Only the fields sent change")
    @ApiResponse(responseCode = "200", description = "The organization after the change, with its new ETag")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid or null name, or unknown properties",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller's role does not allow it",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "It does not exist, it is deleted or the caller is not a member",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Another request changed it at the same time",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "412",
            description = "If-Match does not match the current version",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<OrganizationResponse> update(
            CurrentUser user,
            @PathVariable UUID orgId,
            @Parameter(description = "Optional: the ETag last read. A different version gives 412")
                    @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                    @Nullable
                    String ifMatch,
            @Valid @RequestBody UpdateOrganizationRequest request) {
        return withETag(organizations.rename(user.id(), orgId, request.name(), ifMatch));
    }

    @DeleteMapping("/{orgId}")
    @Operation(
            summary = "Delete an organization",
            description = "Needs ORGANIZATION_DELETE (OWNER only). It disappears for every member")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(
            responseCode = "403",
            description = "The caller's role does not allow it",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "It does not exist, it is already deleted or the caller is not a member",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable UUID orgId) {
        organizations.delete(user.id(), orgId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<OrganizationResponse> withETag(OrganizationWithRole view) {
        return ResponseEntity.ok()
                .eTag(ETags.of(view.organization().savedVersion()))
                .body(OrganizationResponse.from(view));
    }
}
