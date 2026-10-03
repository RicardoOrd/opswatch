package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.application.ProjectService;
import io.github.ricardoord.opswatch.organization.domain.Project;
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
 * Projects: created and listed under their organization, read and changed by their own id. Someone who is not a member
 * of the organization gets {@code 404} for any of them, deleted ones included (docs/security/authorization-model.md).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Projects")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@ApiResponse(
        responseCode = "401",
        description = "Missing, invalid or expired access token",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
class ProjectController {

    static final Set<String> SORTABLE = Set.of("name", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by("name");

    private final ProjectService projects;

    ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @PostMapping("/organizations/{orgId}/projects")
    @Operation(summary = "Create a project", description = "Needs PROJECT_WRITE in the organization")
    @ApiResponse(responseCode = "201", description = "Created, with its ETag")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid name or description, or unknown properties",
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
            description = "The organization does not exist, is deleted or the caller is not a member",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The organization already has a project with this name, whatever its case",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The organization already has the most projects allowed (20)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<ProjectResponse> create(
            CurrentUser user, @PathVariable UUID orgId, @Valid @RequestBody CreateProjectRequest request) {
        // Bean Validation has already rejected a null name
        Project created =
                projects.create(user.id(), orgId, Objects.requireNonNull(request.name()), request.description());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.id()))
                .eTag(ETags.of(created.savedVersion()))
                .body(ProjectResponse.from(created));
    }

    @GetMapping("/organizations/{orgId}/projects")
    @Operation(summary = "The projects of an organization", description = "Needs PROJECT_READ in the organization")
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
    @ApiResponse(responseCode = "200", description = "A page of projects")
    @ApiResponse(
            responseCode = "400",
            description = "page, size or sort not allowed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "The organization does not exist, is deleted or the caller is not a member",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    PageResponse<ProjectResponse> list(CurrentUser user, @PathVariable UUID orgId, PageQuery page) {
        return PageResponse.from(
                projects.listOf(user.id(), orgId, page.toPageable(SORTABLE, DEFAULT_SORT)), ProjectResponse::from);
    }

    @GetMapping("/projects/{projectId}")
    @Operation(summary = "A project", description = "Needs PROJECT_READ in its organization")
    @ApiResponse(responseCode = "200", description = "The project, with its ETag")
    @ApiResponse(
            responseCode = "404",
            description = "It does not exist, it is deleted or the caller is not a member of its organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<ProjectResponse> get(CurrentUser user, @PathVariable UUID projectId) {
        return withETag(projects.get(user.id(), projectId));
    }

    @PatchMapping("/projects/{projectId}")
    @Operation(
            summary = "Change a project",
            description = "Needs PROJECT_WRITE in its organization. Only the fields sent change")
    @ApiResponse(responseCode = "200", description = "The project after the change, with its new ETag")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid or null name, invalid description, or unknown properties",
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
            description = "It does not exist, it is deleted or the caller is not a member of its organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Another project has the new name, or another request changed it at the same time",
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
    ResponseEntity<ProjectResponse> update(
            CurrentUser user,
            @PathVariable UUID projectId,
            @Parameter(description = "Optional: the ETag last read. A different version gives 412")
                    @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                    @Nullable
                    String ifMatch,
            @Valid @RequestBody UpdateProjectRequest request) {
        return withETag(projects.update(user.id(), projectId, request.name(), request.description(), ifMatch));
    }

    @DeleteMapping("/projects/{projectId}")
    @Operation(
            summary = "Delete a project",
            description = "Needs PROJECT_WRITE in its organization. Its monitors are deleted asynchronously")
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
            description = "It does not exist, it is already deleted or the caller is not a member of its organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable UUID projectId) {
        projects.delete(user.id(), projectId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<ProjectResponse> withETag(Project project) {
        return ResponseEntity.ok().eTag(ETags.of(project.savedVersion())).body(ProjectResponse.from(project));
    }
}
