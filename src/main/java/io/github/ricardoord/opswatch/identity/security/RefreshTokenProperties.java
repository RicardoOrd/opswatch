package io.github.ricardoord.opswatch.identity.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Refresh token lifetimes and cookie (docs/devops/environments.md#seguridad).
 *
 * @param ttl lifetime of each token; a rotation starts a new one
 * @param familyMaxTtl lifetime of the whole family from the login, however often it rotates
 */
@ConfigurationProperties("opswatch.security.refresh-token")
public record RefreshTokenProperties(
        @DefaultValue("14d") Duration ttl,
        @DefaultValue("30d") Duration familyMaxTtl,
        @DefaultValue("opswatch_refresh") String cookieName) {

    public RefreshTokenProperties {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(familyMaxTtl) > 0) {
            throw new IllegalArgumentException(
                    "opswatch.security.refresh-token.ttl must be positive and no longer than family-max-ttl");
        }
    }
}
