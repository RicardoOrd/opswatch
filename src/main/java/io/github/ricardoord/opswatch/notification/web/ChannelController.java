package io.github.ricardoord.opswatch.notification.web;

import io.github.ricardoord.opswatch.notification.application.ChannelService;
import io.github.ricardoord.opswatch.notification.application.ChannelView;
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
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification channels: created and listed under their organization, read, changed, deleted and given a new signing
 * secret by their own id. The configuration always comes back masked; the signing secret of a webhook only in the
 * response that creates it or rotates it. Someone who is not a member of the organization gets {@code 404}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Notification channels")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@ApiResponse(
        responseCode = "401",
        description = "Missing, invalid or expired access token",
        content =
                @Content(
                        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
class ChannelController {

    private static final Set<String> SORTABLE = Set.of("name", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by("name");

    private final ChannelService channels;

    ChannelController(ChannelService channels) {
        this.channels = channels;
    }

    @PostMapping("/organizations/{orgId}/notification-channels")
    @Operation(
            summary = "Create a notification channel",
            description = "Needs CHANNEL_WRITE. An EMAIL channel takes email.recipients; a WEBHOOK channel takes"
                    + " webhook.url, https only, and gets a signing secret that this response shows once")
    @ApiResponse(responseCode = "201", description = "Created, enabled, with its ETag")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid fields, the part of the other type, repeated or too many recipients, or unknown"
                    + " properties",
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
            description = "The organization or the project does not exist, is deleted, the project is of another"
                    + " organization, or the caller is not a member",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "target-not-allowed: the SSRF policy rejects the URL, also for not being https."
                    + " quota-exceeded: the organization already has the most channels allowed (10)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<ChannelResponse> create(
            CurrentUser user, @PathVariable UUID orgId, @Valid @RequestBody CreateChannelRequest request) {
        // Bean Validation has already rejected a null name or type
        ChannelView created = channels.create(
                user.id(), orgId, Objects.requireNonNull(request.name()), request.projectId(), request.destination());
        return ResponseEntity.created(URI.create(
                        "/api/v1/notification-channels/" + created.channel().id()))
                .eTag(ETags.of(Objects.requireNonNull(created.channel().savedVersion())))
                .body(ChannelResponse.from(created));
    }

    @GetMapping("/organizations/{orgId}/notification-channels")
    @Operation(summary = "The notification channels of an organization", description = "Needs CHANNEL_READ")
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
    @ApiResponse(responseCode = "200", description = "A page of channels, with their configuration masked")
    @ApiResponse(
            responseCode = "400",
            description = "page, size or sort not allowed",
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
    PageResponse<ChannelResponse> list(CurrentUser user, @PathVariable UUID orgId, PageQuery page) {
        return PageResponse.from(
                channels.listOf(user.id(), orgId, page.toPageable(SORTABLE, DEFAULT_SORT)), ChannelResponse::from);
    }

    @GetMapping("/notification-channels/{channelId}")
    @Operation(summary = "A notification channel", description = "Needs CHANNEL_READ in its organization")
    @ApiResponse(responseCode = "200", description = "The channel, with its configuration masked and its ETag")
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
    ResponseEntity<ChannelResponse> get(CurrentUser user, @PathVariable UUID channelId) {
        return withETag(channels.get(user.id(), channelId));
    }

    @PatchMapping("/notification-channels/{channelId}")
    @Operation(
            summary = "Change a notification channel",
            description = "Needs CHANNEL_WRITE in its organization. Only the fields sent change; the type never does."
                    + " A new webhook URL keeps the signing secret")
    @ApiResponse(responseCode = "200", description = "The channel after the change, with its new ETag")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid fields, null where it is not allowed, the part of the other type, repeated or too"
                    + " many recipients, or unknown properties",
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
            description = "The channel or the new project does not exist, or the caller is not a member of its"
                    + " organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Changed by someone else at the same time",
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
    ResponseEntity<ChannelResponse> update(
            CurrentUser user,
            @PathVariable UUID channelId,
            @Valid @RequestBody UpdateChannelRequest request,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(channels.update(
                user.id(),
                channelId,
                request.name(),
                request.enabled(),
                request.projectId(),
                request.destination(),
                ifMatch));
    }

    @DeleteMapping("/notification-channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete a notification channel",
            description = "Needs CHANNEL_WRITE in its organization. Its deliveries go with it")
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
            description = "It does not exist or the caller is not a member of its organization",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    void delete(CurrentUser user, @PathVariable UUID channelId) {
        channels.delete(user.id(), channelId);
    }

    @PostMapping("/notification-channels/{channelId}/rotate-secret")
    @Operation(
            summary = "Give a webhook a new signing secret",
            description =
                    "Needs CHANNEL_WRITE in its organization. The old secret stops working at once; the new one is"
                            + " in this response only")
    @ApiResponse(responseCode = "200", description = "The channel with its new secret, once, and its new ETag")
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
            description = "It is not a webhook",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<ChannelResponse> rotateSecret(CurrentUser user, @PathVariable UUID channelId) {
        return withETag(channels.rotateSecret(user.id(), channelId));
    }

    private static ResponseEntity<ChannelResponse> withETag(ChannelView view) {
        return ResponseEntity.ok()
                .eTag(ETags.of(Objects.requireNonNull(view.channel().savedVersion())))
                .body(ChannelResponse.from(view));
    }
}
