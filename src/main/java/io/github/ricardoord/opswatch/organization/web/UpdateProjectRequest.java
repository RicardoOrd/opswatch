package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.domain.Project;
import io.github.ricardoord.opswatch.shared.text.VisibleText;
import io.github.ricardoord.opswatch.shared.web.NotNullIfPresent;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of {@code PATCH /api/v1/projects/{projectId}}. An absent field does not change. A null name is rejected; a null
 * description removes it.
 */
public record UpdateProjectRequest(
        @JsonDeserialize(using = NotNullIfPresent.class)
        @Size(min = 1, max = Project.NAME_MAX_LENGTH)
        @Pattern(regexp = VisibleText.PATTERN, message = CreateOrganizationRequest.VISIBLE_TEXT)
        @Nullable
        String name,

        @Schema(
                implementation = String.class,
                nullable = true,
                maxLength = Project.DESCRIPTION_MAX_LENGTH,
                description = "Absent: unchanged. null or blank: removed")
        PatchField<
                        @Size(max = Project.DESCRIPTION_MAX_LENGTH)
                        @Pattern(regexp = VisibleText.PATTERN, message = CreateOrganizationRequest.VISIBLE_TEXT) String>
                description) {

    /** Surrounding spaces are a typing slip; a blank description is none. */
    public UpdateProjectRequest {
        name = name == null ? null : name.strip();
        description = description == null
                ? PatchField.absent()
                : description.map(text -> text.isBlank() ? null : text.strip());
    }
}
