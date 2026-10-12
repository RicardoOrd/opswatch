package io.github.ricardoord.opswatch.notification.delivery;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The webhook notifications (docs/devops/environments.md#notificaciones).
 *
 * @param timeout over the whole send, from connecting to the status of the response: a receiver that takes longer
 *     is a failed attempt (T-33)
 * @param userAgent sent with every webhook, so that a receiver can tell who calls. Its default, with the version, is in
 *     application.yml
 */
@ConfigurationProperties("opswatch.notification.webhook")
public record WebhookProperties(@DefaultValue("5s") Duration timeout, String userAgent) {

    public WebhookProperties {
        Objects.requireNonNull(userAgent, "opswatch.notification.webhook.user-agent is required");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("opswatch.notification.webhook.timeout must be positive");
        }
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("opswatch.notification.webhook.user-agent must not be blank");
        }
    }
}
