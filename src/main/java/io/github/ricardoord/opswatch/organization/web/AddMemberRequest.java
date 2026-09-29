package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/organizations/{orgId}/members}. Only the shape of the email is checked: whether it is
 * registered is the answer. V1 adds users who already have an account.
 */
public record AddMemberRequest(
        @NotBlank @Size(max = 254) @Nullable String email,
        @NotNull @Nullable Role role) {}
