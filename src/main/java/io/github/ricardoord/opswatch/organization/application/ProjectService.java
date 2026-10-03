package io.github.ricardoord.opswatch.organization.application;

import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.organization.ProjectDeleted;
import io.github.ricardoord.opswatch.organization.ProjectRef;
import io.github.ricardoord.opswatch.organization.domain.Organization;
import io.github.ricardoord.opswatch.organization.domain.OrganizationRepository;
import io.github.ricardoord.opswatch.organization.domain.Project;
import io.github.ricardoord.opswatch.organization.domain.ProjectRepository;
import io.github.ricardoord.opswatch.shared.error.ConflictException;
import io.github.ricardoord.opswatch.shared.error.QuotaExceededException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import io.github.ricardoord.opswatch.shared.web.ETags;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import java.time.Clock;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects as the members of their organization see them. Every use case loads the organization or the project first
 * and then asks {@link AccessControl}: the organization never comes from data the client chose.
 */
@Service
public class ProjectService {

    static final String NAME_TAKEN = "The organization already has a project with this name.";

    private final ProjectRepository projects;
    private final OrganizationRepository organizations;
    private final AccessControl access;
    private final IdGenerator ids;
    private final ApplicationEventPublisher events;
    private final OrganizationLimits limits;
    private final Clock clock;

    public ProjectService(
            ProjectRepository projects,
            OrganizationRepository organizations,
            AccessControl access,
            IdGenerator ids,
            ApplicationEventPublisher events,
            OrganizationLimits limits,
            Clock clock) {
        this.projects = projects;
        this.organizations = organizations;
        this.access = access;
        this.ids = ids;
        this.events = events;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Creations in one organization take turns on its row ({@code FOR UPDATE}), the same lock its deletion takes: the
     * quota and the name check hold with simultaneous requests, and no project is born in an organization that is
     * being deleted. The user is authorized before taking the lock, so that outsiders never hold it, and again with it
     * taken, where the role can no longer change.
     *
     * @param description null for none
     * @throws ConflictException if the organization already has a project with that name, whatever its case (409)
     * @throws QuotaExceededException if the organization has as many projects as allowed (422)
     */
    @Transactional
    public Project create(UUID userId, UUID organizationId, String name, @Nullable String description) {
        access.require(userId, organizationId, Permission.PROJECT_WRITE);
        Organization organization = organizations
                .findActiveByIdForUpdate(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("organization", organizationId));
        access.require(userId, organization.id(), Permission.PROJECT_WRITE);
        int limit = limits.projectsPerOrganization();
        if (projects.countByOrganizationIdAndDeletedAtIsNull(organization.id()) >= limit) {
            throw new QuotaExceededException("The organization already has " + limit + " projects, the most allowed.");
        }
        Project project = Project.create(ids.next(), organization.id(), name, description, clock);
        if (projects.existsActiveName(organization.id(), project.name())) {
            throw new ConflictException(NAME_TAKEN);
        }
        projects.save(project);
        // Assigns the version for the ETag of the response
        flushTranslatingDuplicateName();
        return project;
    }

    /** Only the projects of an organization the user is a member of. */
    @Transactional(readOnly = true)
    public Page<Project> listOf(UUID userId, UUID organizationId, Pageable pageable) {
        Organization organization = organizations
                .findByIdAndDeletedAtIsNull(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("organization", organizationId));
        access.require(userId, organization.id(), Permission.PROJECT_READ);
        return projects.findByOrganizationIdAndDeletedAtIsNull(organization.id(), pageable);
    }

    @Transactional(readOnly = true)
    public Project get(UUID userId, UUID projectId) {
        return authorized(userId, projectId, Permission.PROJECT_READ);
    }

    /**
     * @param name null to keep it
     * @param description absent to keep it, null to remove it
     * @param ifMatch the {@code If-Match} header, if the client sent one
     * @throws ConflictException if another project of the organization has the new name (409)
     */
    @Transactional
    public Project update(
            UUID userId,
            UUID projectId,
            @Nullable String name,
            PatchField<String> description,
            @Nullable String ifMatch) {
        Project project = authorized(userId, projectId, Permission.PROJECT_WRITE);
        ETags.requireMatch(ifMatch, project.savedVersion());
        if (name != null) {
            project.rename(name, clock);
        }
        if (description.isSent()) {
            project.describe(description.value(), clock);
        }
        // Fails here on a concurrent change (@Version) or a taken name, and gives the response its new version
        flushTranslatingDuplicateName();
        return project;
    }

    /** Logical. Its monitors are deleted in response to {@link ProjectDeleted}. */
    @Transactional
    public void delete(UUID userId, UUID projectId) {
        Project project = authorized(userId, projectId, Permission.PROJECT_WRITE);
        delete(project, userId);
    }

    /**
     * Every project of an organization being deleted, in the caller's transaction: the caller has already authorized
     * the deletion and locked the organization.
     */
    void deleteAllOf(UUID organizationId, UUID deletedBy) {
        for (Project project : projects.findAllByOrganizationIdAndDeletedAtIsNull(organizationId)) {
            delete(project, deletedBy);
        }
    }

    private void delete(Project project, UUID deletedBy) {
        project.delete(clock);
        events.publishEvent(new ProjectDeleted(project.id(), project.organizationId(), clock.instant(), deletedBy));
    }

    /** A non-member gets the 404 of a missing project, never one that names its organization. */
    private Project authorized(UUID userId, UUID projectId, Permission permission) {
        ProjectRef ref = access.requireForProject(userId, projectId, permission);
        return projects.findByIdAndDeletedAtIsNull(ref.id())
                .orElseThrow(() -> new ResourceNotFoundException("project", projectId));
    }

    /**
     * The unique index settles what the check before saving cannot: a rename, or a creation in a race the lock does
     * not cover.
     */
    private void flushTranslatingDuplicateName() {
        try {
            projects.flush();
        } catch (DataIntegrityViolationException ex) {
            if (ProjectRepository.UNIQUE_NAME_INDEX.equals(violatedConstraint(ex))) {
                throw new ConflictException(NAME_TAKEN);
            }
            throw ex;
        }
    }

    private static @Nullable String violatedConstraint(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
