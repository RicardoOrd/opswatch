package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.security.AccessToken;

/**
 * A successful login. The client keeps the token in memory, never in {@code localStorage}
 * (docs/security/security-architecture.md#dónde-guarda-el-cliente-cada-token).
 *
 * @param expiresIn seconds
 */
public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {

    static AccessTokenResponse from(AccessToken token) {
        return new AccessTokenResponse(
                token.value(), "Bearer", token.expiresIn().toSeconds());
    }

    /** Never includes the token. */
    @Override
    public String toString() {
        return "AccessTokenResponse[accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
