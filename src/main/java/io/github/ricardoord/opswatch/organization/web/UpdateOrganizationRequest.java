package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.domain.Organization;
import io.github.ricardoord.opswatch.shared.text.VisibleText;
import io.github.ricardoord.opswatch.shared.web.NotNullIfPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Body of {@code PATCH /api/v1/organizations/{orgId}}. An absent name does not change; a null one is rejected. */
public record UpdateOrganizationRequest(
        @JsonDeserialize(using = NotNullIfPresent.class)
        @Size(min = 1, max = Organization.NAME_MAX_LENGTH)
        @Pattern(regexp = VisibleText.PATTERN, message = CreateOrganizationRequest.VISIBLE_TEXT)
        @Nullable
        String name) {

    public UpdateOrganizationRequest {
        name = name == null ? null : name.strip();
    }
}
