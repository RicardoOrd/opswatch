package io.github.ricardoord.opswatch.notification.web;

import io.github.ricardoord.opswatch.notification.application.DeliveryService;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deliveries of a notification channel, and its test. Someone who is not a member of the organization of the
 * channel gets {@code 404}.
 */
@RestController
@RequestMapping("/api/v1/notification-channels/{channelId}")
@Tag(name = "Notification channels")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@ApiResponse(
        responseCode = "401",
        description = "Missing, invalid or expired access token",
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
        description = "The channel does not exist or the caller is not a member of its organization",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
class DeliveryController {

    private static final Set<String> SORTABLE = Set.of("createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final DeliveryService deliveries;

    DeliveryController(DeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Send a test notification",
            description =
                    "Needs CHANNEL_WRITE in its organization. Creates a TEST delivery, which is sent as any other:"
                            + " its result shows in the deliveries of the channel. A disabled channel gets it as FAILED")
    @ApiResponse(responseCode = "202", description = "The delivery, PENDING until it is sent")
    @ApiResponse(
            responseCode = "429",
            description = "The channel had 5 tests in the last minute; Retry-After says when to try again",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    DeliveryResponse test(CurrentUser user, @PathVariable UUID channelId) {
        return DeliveryResponse.from(deliveries.test(user.id(), channelId));
    }

    @GetMapping("/deliveries")
    @Operation(
            summary = "The deliveries of a notification channel",
            description = "Needs CHANNEL_READ in its organization. Their status and attempts, never their content")
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
            description = "createdAt, with ,asc or ,desc. The newest first if absent",
            schema = @Schema(type = "string", example = "createdAt,desc"))
    @ApiResponse(responseCode = "200", description = "A page of deliveries")
    @ApiResponse(
            responseCode = "400",
            description = "page, size or sort not allowed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    PageResponse<DeliveryResponse> list(CurrentUser user, @PathVariable UUID channelId, PageQuery page) {
        return PageResponse.from(
                deliveries.deliveriesOf(user.id(), channelId, page.toPageable(SORTABLE, DEFAULT_SORT)),
                DeliveryResponse::from);
    }
}
