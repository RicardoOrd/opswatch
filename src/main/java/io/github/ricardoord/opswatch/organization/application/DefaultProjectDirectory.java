package io.github.ricardoord.opswatch.organization.application;

import io.github.ricardoord.opswatch.organization.ProjectDirectory;
import io.github.ricardoord.opswatch.organization.ProjectRef;
import io.github.ricardoord.opswatch.organization.domain.Project;
import io.github.ricardoord.opswatch.organization.domain.ProjectRepository;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultProjectDirectory implements ProjectDirectory {

    private final ProjectRepository projects;

    DefaultProjectDirectory(ProjectRepository projects) {
        this.projects = projects;
    }

    /** Only inside the caller's transaction: a lock taken in a transaction of its own would end on return. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectRef lockActive(UUID projectId) {
        Project project = projects.findActiveByIdForShare(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("project", projectId));
        return new ProjectRef(project.id(), project.organizationId());
    }
}
