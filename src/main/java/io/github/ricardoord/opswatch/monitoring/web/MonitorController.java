package io.github.ricardoord.opswatch.monitoring.web;

import io.github.ricardoord.opswatch.monitoring.application.MonitorService;
import io.github.ricardoord.opswatch.monitoring.application.MonitorView;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Monitors: created, listed and summed up under their project, read, changed, paused, resumed and deleted by their own
 * id. Someone who is not a
 * member of the organization gets {@code 404} for any of them (docs/security/authorization-model.md).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Monitors")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@ApiResponse(
        responseCode = "401",
        description = "Missing, invalid or expired access token",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
class MonitorController {

    /** API name to the property of the listing query: the status lives in the state ({@code s}). */
    static final Map<String, String> SORTABLE = Map.of("name", "name", "status", "s.status", "createdAt", "createdAt");

    private static final Sort DEFAULT_SORT = Sort.by("name");
    private static final Sort TIE_BREAKER = Sort.by("id");

    private final MonitorService monitors;

    MonitorController(MonitorService monitors) {
        this.monitors = monitors;
    }

    @PostMapping("/projects/{projectId}/monitors")
    @Operation(
            summary = "Create a monitor",
            description = "Needs MONITOR_WRITE in the organization of the project. It starts PENDING")
    @ApiResponse(responseCode = "201", description = "Created, with its ETag")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid fields, rules between fields broken (timeoutMs not below the interval…), a header"
                    + " that is not allowed, or unknown properties",
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
            description = "The project does not exist, is deleted or the caller is not a member of its organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The project already has a monitor with this name, whatever its case",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "target-not-allowed: the SSRF policy rejects the URL. quota-exceeded: the organization"
                    + " already has the most monitors allowed (50)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<MonitorResponse> create(
            CurrentUser user, @PathVariable UUID projectId, @Valid @RequestBody CreateMonitorRequest request) {
        // Bean Validation has already rejected a null name or URL
        MonitorView created = monitors.create(
                user.id(),
                projectId,
                Objects.requireNonNull(request.name()),
                Objects.requireNonNull(request.url()),
                request.settings(),
                request.requestHeaders());
        return ResponseEntity.created(
                        URI.create("/api/v1/monitors/" + created.monitor().id()))
                .eTag(ETags.of(created.monitor().savedVersion()))
                .body(MonitorResponse.from(created));
    }

    @GetMapping("/projects/{projectId}/monitors")
    @Operation(
            summary = "The monitors of a project",
            description = "Needs MONITOR_READ in the organization of the project")
    @Parameter(
            in = ParameterIn.QUERY,
            name = "status",
            description = "Only the monitors in this status",
            schema = @Schema(implementation = MonitorStatus.class))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "q",
            description = "Part of the name, whatever its case. % and _ are not wildcards",
            schema = @Schema(type = "string", maxLength = 100))
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
            description = "name, status or createdAt, with ,asc or ,desc. Repeatable. By name if absent",
            schema = @Schema(type = "string", example = "name,asc"))
    @ApiResponse(responseCode = "200", description = "A page of monitors")
    @ApiResponse(
            responseCode = "400",
            description = "status, q, page, size or sort not allowed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "The project does not exist, is deleted or the caller is not a member of its organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    PageResponse<MonitorResponse> list(
            CurrentUser user,
            @PathVariable UUID projectId,
            @Parameter(hidden = true) @RequestParam(required = false) @Nullable MonitorStatus status,
            @Parameter(hidden = true) @RequestParam(required = false) @Nullable String q,
            PageQuery page) {
        return PageResponse.from(
                monitors.listOf(user.id(), projectId, status, q, page.toPageable(SORTABLE, DEFAULT_SORT, TIE_BREAKER)),
                MonitorResponse::from);
    }

    @GetMapping("/projects/{projectId}/monitors/summary")
    @Operation(
            summary = "The monitors of a project by status",
            description = "Needs MONITOR_READ in the organization of the project")
    @ApiResponse(responseCode = "200", description = "How many monitors are in each status, every status included")
    @ApiResponse(
            responseCode = "404",
            description = "The project does not exist, is deleted or the caller is not a member of its organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    MonitorSummaryResponse summary(CurrentUser user, @PathVariable UUID projectId) {
        return MonitorSummaryResponse.from(monitors.summaryOf(user.id(), projectId));
    }

    @GetMapping("/monitors/{monitorId}")
    @Operation(summary = "A monitor", description = "Needs MONITOR_READ in the organization of its project")
    @ApiResponse(responseCode = "200", description = "The monitor and its state, with its ETag")
    @ApiResponse(
            responseCode = "404",
            description = "It does not exist, it is deleted or the caller is not a member of its organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<MonitorResponse> get(CurrentUser user, @PathVariable UUID monitorId) {
        return withETag(monitors.get(user.id(), monitorId));
    }

    @PatchMapping("/monitors/{monitorId}")
    @Operation(
            summary = "Change a monitor",
            description = "Needs MONITOR_WRITE in the organization of its project. Only the fields sent change, and the"
                    + " rules between fields are checked on the result")
    @ApiResponse(responseCode = "200", description = "The monitor after the change, with its new ETag")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid or null fields, rules between fields broken on the result, a header that is not"
                    + " allowed, or unknown properties",
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
            description = "Another monitor of the project has the new name, or another request changed it at the same"
                    + " time",
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
    @ApiResponse(
            responseCode = "422",
            description = "target-not-allowed: the SSRF policy rejects the new URL",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<MonitorResponse> update(
            CurrentUser user,
            @PathVariable UUID monitorId,
            @Parameter(description = "Optional: the ETag last read. A different version gives 412")
                    @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                    @Nullable
                    String ifMatch,
            @Valid @RequestBody UpdateMonitorRequest request) {
        return withETag(monitors.update(
                user.id(),
                monitorId,
                request.name(),
                request.url(),
                request.settings(),
                request.requestHeaders(),
                ifMatch));
    }

    @PostMapping("/monitors/{monitorId}/pause")
    @Operation(
            summary = "Pause a monitor",
            description = "Needs MONITOR_WRITE in the organization of its project. It is not checked until resumed")
    @ApiResponse(responseCode = "200", description = "The monitor, PAUSED and unscheduled")
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
            description = "It is already paused",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<MonitorResponse> pause(CurrentUser user, @PathVariable UUID monitorId) {
        return withETag(monitors.pause(user.id(), monitorId));
    }

    @PostMapping("/monitors/{monitorId}/resume")
    @Operation(
            summary = "Resume a paused monitor",
            description = "Needs MONITOR_WRITE in the organization of its project. It starts PENDING again, with its"
                    + " first check within 30 s")
    @ApiResponse(responseCode = "200", description = "The monitor, PENDING and scheduled")
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
            description = "It is not paused",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<MonitorResponse> resume(CurrentUser user, @PathVariable UUID monitorId) {
        return withETag(monitors.resume(user.id(), monitorId));
    }

    @DeleteMapping("/monitors/{monitorId}")
    @Operation(
            summary = "Delete a monitor",
            description = "Needs MONITOR_WRITE in the organization of its project. Its history stays")
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
    @ApiResponse(
            responseCode = "409",
            description = "Another request changed it at the same time",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable UUID monitorId) {
        monitors.delete(user.id(), monitorId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<MonitorResponse> withETag(MonitorView monitor) {
        return ResponseEntity.ok()
                .eTag(ETags.of(monitor.monitor().savedVersion()))
                .body(MonitorResponse.from(monitor));
    }
}
