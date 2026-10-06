package io.github.ricardoord.opswatch.notification.web;

import io.github.ricardoord.opswatch.notification.application.ChannelDestination;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannel;
import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import io.github.ricardoord.opswatch.shared.text.VisibleText;
import io.github.ricardoord.opswatch.shared.web.NotNullIfPresent;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of {@code PATCH /api/v1/notification-channels/{channelId}}. An absent field does not change; the type never does.
 * {@code projectId} admits null, which makes the channel receive the incidents of every project. {@code email} or
 * {@code webhook}, the one of the type of the channel, replaces that part whole; a new URL keeps the signing secret.
 */
public record UpdateChannelRequest(
        @JsonDeserialize(using = NotNullIfPresent.class)
        @Size(min = 1, max = NotificationChannel.NAME_MAX_LENGTH)
        @Pattern(regexp = VisibleText.PATTERN, message = VisibleText.MESSAGE)
        @Nullable
        String name,

        @JsonDeserialize(using = NotNullIfPresent.class) @Nullable
        Boolean enabled,

        @Schema(
                implementation = UUID.class,
                nullable = true,
                description = "Absent: unchanged. null: every project of the organization")
        PatchField<UUID> projectId,

        @JsonDeserialize(using = NotNullIfPresent.class) @Valid @Nullable
        EmailInput email,

        @JsonDeserialize(using = NotNullIfPresent.class) @Valid @Nullable
        WebhookInput webhook) {

    /** Surrounding spaces are a typing slip. */
    public UpdateChannelRequest {
        name = name == null ? null : name.strip();
        projectId = projectId == null ? PatchField.absent() : projectId;
    }

    /**
     * Bean Validation has already checked each part.
     *
     * @return null to keep it
     * @throws InvalidFieldException if both parts are there (400)
     */
    @Nullable
    ChannelDestination destination() {
        if (email != null && webhook != null) {
            throw new InvalidFieldException("webhook", "not-applicable", "cannot change with email");
        }
        if (email != null) {
            return new ChannelDestination.Email(Objects.requireNonNull(email.recipients()));
        }
        if (webhook != null) {
            return new ChannelDestination.Webhook(Objects.requireNonNull(webhook.url()));
        }
        return null;
    }
}
