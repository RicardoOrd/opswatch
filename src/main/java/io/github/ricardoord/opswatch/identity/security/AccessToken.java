package io.github.ricardoord.opswatch.identity.security;

import java.time.Duration;

/** A signed access token and how long it stays valid. */
public record AccessToken(String value, Duration expiresIn) {

    /** Never includes the token: whoever reads a log must not be able to use it. */
    @Override
    public String toString() {
        return "AccessToken[value=<redacted>, expiresIn=" + expiresIn + "]";
    }
}
