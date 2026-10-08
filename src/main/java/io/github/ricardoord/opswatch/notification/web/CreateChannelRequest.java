package io.github.ricardoord.opswatch.notification.web;

import io.github.ricardoord.opswatch.notification.application.ChannelDestination;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannel;
import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import io.github.ricardoord.opswatch.shared.text.VisibleText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/organizations/{orgId}/notification-channels}. The organization comes from the path. It
 * carries {@code email} or {@code webhook}, the one of its {@code type}.
 *
 * @param projectId a project of the organization; absent or null for every project
 */
public record CreateChannelRequest(
        @NotBlank
        @Size(max = NotificationChannel.NAME_MAX_LENGTH)
        @Pattern(regexp = VisibleText.PATTERN, message = VisibleText.MESSAGE)
        @Nullable
        String name,

        @NotNull @Nullable ChannelType type,
        @Nullable UUID projectId,
        @Valid @Nullable EmailInput email,
        @Valid @Nullable WebhookInput webhook) {

    /** Surrounding spaces are a typing slip. */
    public CreateChannelRequest {
        name = name == null ? null : name.strip();
    }

    /**
     * Bean Validation has already checked each part.
     *
     * @throws InvalidFieldException if the part of the type is missing, or the other one is there (400)
     */
    ChannelDestination destination() {
        ChannelType channelType = Objects.requireNonNull(type);
        if (channelType == ChannelType.EMAIL) {
            requireAbsent(webhook, "webhook", channelType);
            return new ChannelDestination.Email(
                    Objects.requireNonNull(required(email, "email").recipients()));
        }
        requireAbsent(email, "email", channelType);
        return new ChannelDestination.Webhook(
                Objects.requireNonNull(required(webhook, "webhook").url()));
    }

    private static <T> T required(@Nullable T part, String field) {
        if (part == null) {
            throw new InvalidFieldException(field, "not-null", "must not be null");
        }
        return part;
    }

    private static void requireAbsent(@Nullable Object part, String field, ChannelType type) {
        if (part != null) {
            throw new InvalidFieldException(field, "not-applicable", "does not apply to a " + type + " channel");
        }
    }
}
