package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.application.Member;
import io.github.ricardoord.opswatch.organization.application.MembershipService;
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
import java.util.Map;
import java.util.Objects;
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
 * Members of an organization and their roles. Someone who is not a member gets {@code 404}, as for the organization
 * itself.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/members")
@Tag(name = "Members")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@ApiResponse(
        responseCode = "401",
        description = "Missing, invalid or expired access token",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
        responseCode = "404",
        description = "The organization does not exist, is deleted or the caller is not a member; or the user is not "
                + "a member",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
class MemberController {

    /** The API name of each sortable field and the entity property behind it. */
    private static final Map<String, String> SORTABLE = Map.of("joinedAt", "createdAt");

    private static final Sort DEFAULT_SORT = Sort.by("createdAt");
    private static final Sort TIE_BREAKER = Sort.by("userId");

    private final MembershipService members;

    MemberController(MembershipService members) {
        this.members = members;
    }

    @GetMapping
    @Operation(summary = "The members of an organization", description = "Needs MEMBER_READ")
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
            description = "joinedAt, with ,asc or ,desc. By joinedAt if absent",
            schema = @Schema(type = "string", example = "joinedAt,desc"))
    @ApiResponse(responseCode = "200", description = "A page of members")
    @ApiResponse(
            responseCode = "400",
            description = "page, size or sort not allowed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    PageResponse<MemberResponse> list(CurrentUser user, @PathVariable UUID orgId, PageQuery page) {
        return PageResponse.from(
                members.list(user.id(), orgId, page.toPageable(SORTABLE, DEFAULT_SORT, TIE_BREAKER)),
                MemberResponse::from);
    }

    @PostMapping
    @Operation(
            summary = "Add a member",
            description = "Someone who already has an account. MEMBER and VIEWER need MEMBER_MANAGE_BASIC; ADMIN and "
                    + "OWNER, MEMBER_MANAGE_PRIVILEGED")
    @ApiResponse(responseCode = "201", description = "Added, with its ETag")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid email or role",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller's role cannot give that role",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The user is already a member",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The organization has the most members allowed (50)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<MemberResponse> add(
            CurrentUser user, @PathVariable UUID orgId, @Valid @RequestBody AddMemberRequest request) {
        // Bean Validation has already rejected null fields
        Member added = members.add(
                user.id(), orgId, Objects.requireNonNull(request.email()), Objects.requireNonNull(request.role()));
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + orgId + "/members/"
                        + added.user().id()))
                .eTag(ETags.of(added.membership().savedVersion()))
                .body(MemberResponse.from(added));
    }

    @PatchMapping("/{userId}")
    @Operation(
            summary = "Change the role of a member",
            description = "Touching OWNER or ADMIN, before or after, needs MEMBER_MANAGE_PRIVILEGED; the rest, "
                    + "MEMBER_MANAGE_BASIC. Nobody raises their own role")
    @ApiResponse(responseCode = "200", description = "The member after the change, with its new ETag")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid or null role, or unknown properties",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller's role does not allow this change, or it raises the caller's own role",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "It would leave the organization without an OWNER, or another request changed the member at "
                    + "the same time",
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
    ResponseEntity<MemberResponse> update(
            CurrentUser user,
            @PathVariable UUID orgId,
            @PathVariable UUID userId,
            @Parameter(description = "Optional: the ETag last read. A different version gives 412")
                    @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                    @Nullable
                    String ifMatch,
            @Valid @RequestBody UpdateMemberRequest request) {
        Member changed = members.changeRole(user.id(), orgId, userId, request.role(), ifMatch);
        return ResponseEntity.ok()
                .eTag(ETags.of(changed.membership().savedVersion()))
                .body(MemberResponse.from(changed));
    }

    @DeleteMapping("/{userId}")
    @Operation(
            summary = "Remove a member, or leave the organization",
            description = "Any member may leave, except the last OWNER. Removing someone else needs the permission for "
                    + "their role")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(
            responseCode = "403",
            description = "The caller's role cannot remove that member",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "It would leave the organization without an OWNER",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<Void> remove(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID userId) {
        members.remove(user.id(), orgId, userId);
        return ResponseEntity.noContent().build();
    }
}
