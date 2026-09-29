package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Body of {@code POST /api/v1/auth/register}. Validation rules: docs/api/endpoints-v1.md#autenticación-identity. */
public record RegisterUserRequest(
        @NotBlank
        @Size(max = User.EMAIL_MAX_LENGTH)
        @Email(regexp = User.EMAIL_CHARACTERS_PATTERN, message = "must be a valid email address in ASCII")
        @Nullable
        String email,

        @NotBlank
        @Size(max = User.DISPLAY_NAME_MAX_LENGTH)
        @Pattern(
                regexp = User.DISPLAY_NAME_PATTERN,
                message = "must not contain control or bidirectional formatting characters")
        @Nullable
        String displayName,

        @NotNull @PasswordPolicy @Nullable String password) {

    /** Surrounding spaces are a typing slip, not part of the value. The password is kept exactly as sent. */
    public RegisterUserRequest {
        email = email == null ? null : email.strip();
        displayName = displayName == null ? null : displayName.strip();
    }

    /** Never includes the password: requests end up in logs and error reports. */
    @Override
    public String toString() {
        return "RegisterUserRequest[email=" + email + ", displayName=" + displayName + ", password=<redacted>]";
    }
}
