package io.github.ricardoord.opswatch.shared.security;

import java.util.UUID;

/**
 * The authenticated caller: the {@code sub} claim of the access token, nothing more. Controllers receive it as a
 * method parameter. It lives in {@code shared} so that knowing who calls does not require depending on
 * {@code identity}; what they may do is decided per resource by {@code AccessControl}.
 */
public record CurrentUser(UUID id) {}
