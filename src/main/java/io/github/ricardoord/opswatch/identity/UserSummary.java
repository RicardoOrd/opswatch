package io.github.ricardoord.opswatch.identity;

import java.util.UUID;

/** A user as other modules see it: never with credentials. */
public record UserSummary(UUID id, String email, String displayName) {}
