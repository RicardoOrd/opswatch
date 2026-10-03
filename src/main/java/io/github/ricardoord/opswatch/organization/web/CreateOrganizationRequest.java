package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.domain.Organization;
import io.github.ricardoord.opswatch.shared.text.VisibleText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Body of {@code POST /api/v1/organizations}. The name does not have to be unique. */
public record CreateOrganizationRequest(
        @NotBlank
        @Size(max = Organization.NAME_MAX_LENGTH)
        @Pattern(regexp = VisibleText.PATTERN, message = VISIBLE_TEXT)
        @Nullable
        String name) {

    static final String VISIBLE_TEXT = VisibleText.MESSAGE;

    /** Surrounding spaces are a typing slip, not part of the name. */
    public CreateOrganizationRequest {
        name = name == null ? null : name.strip();
    }
}
