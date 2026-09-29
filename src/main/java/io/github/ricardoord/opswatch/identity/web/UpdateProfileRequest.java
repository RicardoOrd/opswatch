package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.shared.web.NotNullIfPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of {@code PATCH /api/v1/me}. An absent field does not change (docs/api/api-guidelines.md). The display name
 * cannot be empty, so an explicit {@code null} is rejected instead of being taken as absent. The email is not
 * editable: an {@code email} property is unknown, so it fails like any other.
 */
public record UpdateProfileRequest(
        @JsonDeserialize(using = NotNullIfPresent.class)
        @Size(min = 1, max = User.DISPLAY_NAME_MAX_LENGTH)
        @Pattern(
                regexp = User.DISPLAY_NAME_PATTERN,
                message = "must not contain control or bidirectional formatting characters")
        @Nullable
        String displayName) {

    /** Surrounding spaces are a typing slip, not part of the name. */
    public UpdateProfileRequest {
        displayName = displayName == null ? null : displayName.strip();
    }
}
