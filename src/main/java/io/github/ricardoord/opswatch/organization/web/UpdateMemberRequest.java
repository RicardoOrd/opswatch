package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.Role;
import io.github.ricardoord.opswatch.shared.web.NotNullIfPresent;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Body of {@code PATCH /api/v1/organizations/{orgId}/members/{userId}}. An absent role does not change. */
public record UpdateMemberRequest(
        @JsonDeserialize(using = NotNullIfPresent.class) @Nullable
        Role role) {}
