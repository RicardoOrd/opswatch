package io.github.ricardoord.opswatch.identity.application;

import java.time.Instant;

/** A refresh token as handed to the client: the only moment its value exists outside the cookie. */
public record IssuedRefreshToken(String value, Instant expiresAt) {

    /** Never includes the token: whoever reads a log must not be able to use it. */
    @Override
    public String toString() {
        return "IssuedRefreshToken[value=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
