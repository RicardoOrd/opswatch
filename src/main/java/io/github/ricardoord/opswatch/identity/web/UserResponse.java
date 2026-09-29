package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.domain.User;
import java.time.Instant;
import java.util.UUID;

/** The account as the API shows it. Never carries the password hash. */
public record UserResponse(UUID id, String email, String displayName, Instant createdAt) {

    static UserResponse from(User user) {
        return new UserResponse(user.id(), user.email(), user.displayName(), user.createdAt());
    }
}
