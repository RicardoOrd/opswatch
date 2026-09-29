package io.github.ricardoord.opswatch.identity.web;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/me/password}. The current password is only checked for shape here, like at login:
 * whether it is right is answered by the service. Both are kept exactly as sent.
 */
public record ChangePasswordRequest(
        @NotEmpty @Size(max = LoginRequest.MAX_PASSWORD_LENGTH) @Nullable
        String currentPassword,

        @NotNull @PasswordPolicy @Nullable String newPassword) {

    /** Never includes the passwords: requests end up in logs and error reports. */
    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=<redacted>, newPassword=<redacted>]";
    }
}
