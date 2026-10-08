package io.github.ricardoord.opswatch.notification.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.ricardoord.opswatch.notification.application.ChannelConfig;
import io.github.ricardoord.opswatch.notification.application.ChannelView;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannel;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A notification channel, with its configuration masked (docs/security/authorization-model.md): every reader sees the
 * same, and nobody reads it back whole. {@code version} is the value of its {@code ETag}, for {@code If-Match}.
 *
 * @param projectId null: every project of the organization
 * @param email only for an {@code EMAIL} channel
 * @param webhook only for a {@code WEBHOOK} channel
 */
public record ChannelResponse(
        UUID id,
        UUID organizationId,
        @Nullable UUID projectId,
        String name,
        ChannelType type,
        boolean enabled,
        @Nullable EmailResponse email,
        @Nullable WebhookResponse webhook,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    static ChannelResponse from(ChannelView view) {
        NotificationChannel channel = view.channel();
        EmailResponse email = null;
        WebhookResponse webhook = null;
        switch (view.config()) {
            case ChannelConfig.Email config ->
                email = new EmailResponse(config.recipients().stream()
                        .map(ChannelResponse::maskEmail)
                        .toList());
            case ChannelConfig.Webhook config ->
                webhook = new WebhookResponse(maskUrl(config.url()), view.showSecret() ? config.signingSecret() : null);
        }
        return new ChannelResponse(
                channel.id(),
                channel.organizationId(),
                channel.projectId(),
                channel.name(),
                channel.type(),
                channel.enabled(),
                email,
                webhook,
                channel.createdAt(),
                channel.updatedAt(),
                Objects.requireNonNull(channel.savedVersion()));
    }

    /** {@code oncall@example.com} → {@code o***@example.com}: enough to tell addresses apart, not to read them. */
    static String maskEmail(String address) {
        int at = address.lastIndexOf('@');
        return address.charAt(0) + "***" + address.substring(at);
    }

    /**
     * Origin only: the path and the query of a webhook often carry a token. {@code https://hooks.example.com/T0/B1/x}
     * → {@code https://hooks.example.com/…}.
     */
    static String maskUrl(String url) {
        URI uri = URI.create(url);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        boolean more = (uri.getRawPath() != null
                        && !uri.getRawPath().isEmpty()
                        && !uri.getRawPath().equals("/"))
                || uri.getRawQuery() != null;
        return more ? origin + "/…" : origin;
    }

    /** @param recipients masked */
    public record EmailResponse(List<String> recipients) {}

    /**
     * @param url masked
     * @param signingSecret only in the response that creates the channel or rotates its secret: keep it then, because
     *     it is never shown again
     */
    public record WebhookResponse(
            String url,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String signingSecret) {}
}
