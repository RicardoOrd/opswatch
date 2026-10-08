package io.github.ricardoord.opswatch.notification.application;

import io.github.ricardoord.opswatch.shared.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The test of a channel (docs/devops/environments.md#notificaciones).
 *
 * @param rateLimit per channel, so that the test cannot be used to send to third parties at will (T-35)
 */
@ConfigurationProperties("opswatch.notification.test")
public record TestNotificationProperties(
        @DefaultValue("5/1m") RateLimit rateLimit) {}
