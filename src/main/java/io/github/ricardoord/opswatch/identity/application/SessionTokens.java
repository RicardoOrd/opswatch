package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.identity.security.AccessToken;

/** What a login or a refresh hands out: an access token for the API and a refresh token for the cookie. */
public record SessionTokens(AccessToken accessToken, IssuedRefreshToken refreshToken) {}
