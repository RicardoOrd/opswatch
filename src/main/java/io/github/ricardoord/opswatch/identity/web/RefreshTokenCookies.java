package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.application.IssuedRefreshToken;
import io.github.ricardoord.opswatch.identity.security.RefreshTokenProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token cookie (docs/security/security-architecture.md): out of reach of JavaScript, only over HTTPS, never
 * sent from another site and only to the authentication endpoints.
 */
@Component
class RefreshTokenCookies {

    static final String PATH = "/api/v1/auth";

    private final RefreshTokenProperties properties;
    private final Clock clock;

    RefreshTokenCookies(RefreshTokenProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    String name() {
        return properties.cookieName();
    }

    /** Lives as long as the token, so the browser drops it when it would be useless anyway. */
    ResponseCookie of(IssuedRefreshToken token) {
        Duration left = Duration.between(clock.instant(), token.expiresAt());
        // Max-Age is whole seconds: rounding up keeps 14 days at 1209600 instead of losing one to the clock's ticks
        long seconds = Math.max(0, left.getSeconds() + (left.getNano() > 0 ? 1 : 0));
        return builder(token.value()).maxAge(seconds).build();
    }

    /** Tells the browser to delete the cookie: same name, path and attributes, and no lifetime left. */
    ResponseCookie cleared() {
        return builder("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder builder(String value) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH);
    }
}
