package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.domain.Project;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A project. {@code version} is the value of its {@code ETag}, for {@code If-Match}. */
public record ProjectResponse(
        UUID id,
        UUID organizationId,
        String name,
        @Nullable String description,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.id(),
                project.organizationId(),
                project.name(),
                project.description(),
                project.createdAt(),
                project.updatedAt(),
                project.savedVersion());
    }
}
