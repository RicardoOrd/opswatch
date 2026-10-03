package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.domain.Project;
import io.github.ricardoord.opswatch.shared.text.VisibleText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/organizations/{orgId}/projects}. The organization comes from the path: a body that
 * names one is rejected as an unknown property.
 */
public record CreateProjectRequest(
        @NotBlank
        @Size(max = Project.NAME_MAX_LENGTH)
        @Pattern(regexp = VisibleText.PATTERN, message = CreateOrganizationRequest.VISIBLE_TEXT)
        @Nullable
        String name,

        @Size(max = Project.DESCRIPTION_MAX_LENGTH)
        @Pattern(regexp = VisibleText.PATTERN, message = CreateOrganizationRequest.VISIBLE_TEXT)
        @Nullable
        String description) {

    /** Surrounding spaces are a typing slip; a blank description is none. */
    public CreateProjectRequest {
        name = name == null ? null : name.strip();
        description = description == null || description.isBlank() ? null : description.strip();
    }
}
