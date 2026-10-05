package io.github.ricardoord.opswatch.monitoring.web;

import io.github.ricardoord.opswatch.monitoring.application.MonitorStatsQueries;
import io.github.ricardoord.opswatch.monitoring.application.StatsWindow;
import io.github.ricardoord.opswatch.monitoring.domain.CheckStatus;
import io.github.ricardoord.opswatch.shared.security.CurrentUser;
import io.github.ricardoord.opswatch.shared.web.CursorPage;
import io.github.ricardoord.opswatch.shared.web.CursorQuery;
import io.github.ricardoord.opswatch.shared.web.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The checks of a monitor and its statistics (docs/api/endpoints-v1.md#checks-y-estadísticas). Someone who is not a
 * member of the organization gets {@code 404}, as for a monitor that does not exist.
 */
@RestController
@RequestMapping("/api/v1/monitors/{monitorId}")
@Tag(name = "Checks")
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
        description = "The monitor does not exist, is deleted or the caller is not a member of its organization",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
class CheckController {

    private final MonitorStatsQueries queries;

    CheckController(MonitorStatsQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/checks")
    @Operation(
            summary = "The checks of a monitor",
            description = "Needs MONITOR_READ in the organization of its project. Newest first, by cursor: pass the"
                    + " nextCursor of a page to get the next one")
    @Parameter(
            in = ParameterIn.QUERY,
            name = "status",
            description = "Only the checks in these statuses, separated by commas",
            schema = @Schema(type = "string", example = "DOWN,DEGRADED"))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "from",
            description = "Only the checks that started at or after this instant (ISO-8601)",
            schema = @Schema(type = "string", format = "date-time"))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "to",
            description = "Only the checks that started before this instant (ISO-8601)",
            schema = @Schema(type = "string", format = "date-time"))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "limit",
            description = "From 1 to 200",
            schema = @Schema(type = "integer", defaultValue = "50", minimum = "1", maximum = "200"))
    @Parameter(
            in = ParameterIn.QUERY,
            name = "cursor",
            description = "The nextCursor of the previous page, as it came",
            schema = @Schema(type = "string"))
    @ApiResponse(responseCode = "200", description = "A page of checks, and the cursor of the next one if any")
    @ApiResponse(
            responseCode = "400",
            description = "status, from, to, limit or cursor not allowed, or from not before to",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    CursorPage<CheckResponse> checks(
            CurrentUser user,
            @PathVariable UUID monitorId,
            @Parameter(hidden = true) @RequestParam(required = false) @Nullable List<CheckStatus> status,
            @Parameter(hidden = true) @RequestParam(required = false) @Nullable Instant from,
            @Parameter(hidden = true) @RequestParam(required = false) @Nullable Instant to,
            CursorQuery page) {
        Set<CheckStatus> statuses = status == null || status.isEmpty() ? Set.of() : EnumSet.copyOf(status);
        return queries.checksOf(user.id(), monitorId, statuses, from, to, page).map(CheckResponse::from);
    }

    @GetMapping("/stats")
    @Operation(
            summary = "The statistics of a monitor",
            description = "Needs MONITOR_READ in the organization of its project. Over the checks that started in the"
                    + " window, which ends now: the time paused counts for nothing")
    @Parameter(
            in = ParameterIn.QUERY,
            name = "window",
            description = "24h, 7d or 30d",
            schema =
                    @Schema(
                            type = "string",
                            allowableValues = {"24h", "7d", "30d"},
                            defaultValue = "24h"))
    @ApiResponse(responseCode = "200", description = "Uptime, response times and failures by reason")
    @ApiResponse(
            responseCode = "400",
            description = "A window other than 24h, 7d or 30d",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    MonitorStatsResponse stats(
            CurrentUser user,
            @PathVariable UUID monitorId,
            @Parameter(hidden = true) @RequestParam(defaultValue = "24h") String window) {
        return MonitorStatsResponse.from(queries.statsOf(user.id(), monitorId, StatsWindow.of(window)));
    }
}
