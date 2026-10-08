package io.github.ricardoord.opswatch.identity.security;

import io.github.ricardoord.opswatch.shared.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Rate limits of the authentication and credential endpoints (docs/devops/environments.md#seguridad). */
@ConfigurationProperties("opswatch.security.rate-limit")
public record RateLimitProperties(
        @DefaultValue("10/1m") RateLimit loginPerIp,
        @DefaultValue("5/1m") RateLimit loginPerEmail,
        @DefaultValue("5/1h") RateLimit registerPerIp,
        @DefaultValue("30/1m") RateLimit refreshPerIp,
        @DefaultValue("5/15m") RateLimit passwordChangePerUser) {}
