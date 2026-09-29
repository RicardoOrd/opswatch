package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/auth/login}. Only the shape is validated: whether the credentials are right is always
 * answered with the same {@code 401}. The size limit only keeps huge bodies away from the hashing code.
 */
public record LoginRequest(
        @NotBlank @Size(max = User.EMAIL_MAX_LENGTH) @Nullable
        String email,

        @NotEmpty @Size(max = MAX_PASSWORD_LENGTH) @Nullable String password) {

    static final int MAX_PASSWORD_LENGTH = 1024;

    public LoginRequest {
        email = email == null ? null : email.strip();
    }

    /** Never includes the password: requests end up in logs and error reports. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=<redacted>]";
    }
}
