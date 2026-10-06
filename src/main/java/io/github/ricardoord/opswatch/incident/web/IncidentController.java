package io.github.ricardoord.opswatch.incident.web;

import io.github.ricardoord.opswatch.incident.application.IncidentService;
import io.github.ricardoord.opswatch.incident.domain.IncidentFilter;
import io.github.ricardoord.opswatch.incident.domain.IncidentStatus;
import io.github.ricardoord.opswatch.shared.security.CurrentUser;
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
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incidents: listed by organization, read and acknowledged by their own id. They open and resolve on their own; there
 * is no manual resolution in V1 (docs/architecture/incident-lifecycle.md#por-qué-no-hay-resolución-manual-r6).
 * Someone who is not a member of the organization gets {@code 404}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Incidents")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@ApiResponse(
        responseCode = "401",
        description = "Missing, invalid or expired access token",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
class IncidentController {

    /** API name to entity property: the same. */
    static final Map<String, String> SORTABLE = Map.of("openedAt", "openedAt", "resolvedAt", "resolvedAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "openedAt");
    private static final Sort TIE_BREAKER = Sort.by("id");

    private final IncidentService incidents;

    IncidentController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping("/organizations/{orgId}/incidents")
    @Operation(
            summary = "The incidents of an organization",
            description = "Needs INCIDENT_READ. Also those of deleted projects and monitors, which are history")
    @Parameter(
            in = ParameterIn.QUERY,
            name = "status",
            description = "Only the incidents in these statuses, separated by commas",
            schema = @Schema(type = "string", example = "OPEN,ACKNOWLEDGED"))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "projectId",
            description = "Only those of this project",
            schema = @Schema(type = "string", format = "uuid"))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "monitorId",
            description = "Only those of this monitor",
            schema = @Schema(type = "string", format = "uuid"))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "from",
            description = "Only those opened at or after this instant (ISO-8601)",
            schema = @Schema(type = "string", format = "date-time"))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "to",
            description = "Only those opened before this instant (ISO-8601)",
            schema = @Schema(type = "string", format = "date-time"))
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
            description = "openedAt or resolvedAt, with ,asc or ,desc. Repeatable. Newest opened first if absent."
                    + " By resolvedAt descending, the active ones come first",
            schema = @Schema(type = "string", example = "openedAt,desc"))
    @ApiResponse(responseCode = "200", description = "A page of incidents, without their timelines")
    @ApiResponse(
            responseCode = "400",
            description =
                    "status, projectId, monitorId, from, to, page, size or sort not allowed, or from not before to",
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
    PageResponse<IncidentSummaryResponse> list(
            CurrentUser user,
            @PathVariable UUID orgId,
            @Parameter(hidden = true) @RequestParam(required = false) @Nullable List<IncidentStatus> status,
            @Parameter(hidden = true) @RequestParam(required = false) @Nullable UUID projectId,
            @Parameter(hidden = true) @RequestParam(required = false) @Nullable UUID monitorId,
            @Parameter(hidden = true) @RequestParam(required = false) @Nullable Instant from,
            @Parameter(hidden = true) @RequestParam(required = false) @Nullable Instant to,
            PageQuery page) {
        Set<IncidentStatus> statuses = status == null || status.isEmpty() ? Set.of() : EnumSet.copyOf(status);
        IncidentFilter filter = new IncidentFilter(statuses, projectId, monitorId, from, to);
        return PageResponse.from(
                incidents.listOf(user.id(), orgId, filter, page.toPageable(SORTABLE, DEFAULT_SORT, TIE_BREAKER)),
                IncidentSummaryResponse::from);
    }

    @GetMapping("/incidents/{incidentId}")
    @Operation(
            summary = "An incident",
            description = "Needs INCIDENT_READ in its organization. With its timeline, oldest first")
    @ApiResponse(responseCode = "200", description = "The incident and its timeline")
    @ApiResponse(
            responseCode = "404",
            description = "It does not exist or the caller is not a member of its organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    IncidentResponse get(CurrentUser user, @PathVariable UUID incidentId) {
        return IncidentResponse.from(incidents.get(user.id(), incidentId));
    }

    @PostMapping("/incidents/{incidentId}/acknowledge")
    @Operation(
            summary = "Acknowledge an open incident",
            description = "Needs INCIDENT_ACKNOWLEDGE in its organization. Says someone is looking into it: it does not"
                    + " change the monitor, and the incident still resolves on its own. The body is optional")
    @ApiResponse(responseCode = "200", description = "The incident, ACKNOWLEDGED, with its timeline")
    @ApiResponse(
            responseCode = "400",
            description = "A note longer than 500 characters or with control characters, or unknown properties",
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
            description = "It does not exist or the caller is not a member of its organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "business-rule-violation: it is not OPEN (already acknowledged, or resolved, also by a"
                    + " recovery at the same time)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    IncidentResponse acknowledge(
            CurrentUser user,
            @PathVariable UUID incidentId,
            @Valid @RequestBody(required = false) @Nullable AcknowledgeIncidentRequest request) {
        return IncidentResponse.from(
                incidents.acknowledge(user.id(), incidentId, request == null ? null : request.note()));
    }
}
