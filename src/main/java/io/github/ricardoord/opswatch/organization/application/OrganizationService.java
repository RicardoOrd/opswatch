package io.github.ricardoord.opswatch.organization.application;

import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.OrganizationDeleted;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.organization.Role;
import io.github.ricardoord.opswatch.organization.domain.Membership;
import io.github.ricardoord.opswatch.organization.domain.MembershipRepository;
import io.github.ricardoord.opswatch.organization.domain.Organization;
import io.github.ricardoord.opswatch.organization.domain.OrganizationRepository;
import io.github.ricardoord.opswatch.organization.domain.OrganizationWithRole;
import io.github.ricardoord.opswatch.shared.error.QuotaExceededException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import io.github.ricardoord.opswatch.shared.lock.AdvisoryLocks;
import io.github.ricardoord.opswatch.shared.lock.LockSpace;
import io.github.ricardoord.opswatch.shared.web.ETags;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organizations as their members see them. Every use case loads the organization first and then asks
 * {@link AccessControl}: the organization never comes from data the client chose.
 */
@Service
@EnableConfigurationProperties(OrganizationLimits.class)
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final ProjectService projects;
    private final AccessControl access;
    private final IdGenerator ids;
    private final AdvisoryLocks locks;
    private final ApplicationEventPublisher events;
    private final OrganizationLimits limits;
    private final Clock clock;

    public OrganizationService(
            OrganizationRepository organizations,
            MembershipRepository memberships,
            ProjectService projects,
            AccessControl access,
            IdGenerator ids,
            AdvisoryLocks locks,
            ApplicationEventPublisher events,
            OrganizationLimits limits,
            Clock clock) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.projects = projects;
        this.access = access;
        this.ids = ids;
        this.locks = locks;
        this.events = events;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * The creator becomes its {@code OWNER} in the same transaction.
     *
     * @throws QuotaExceededException if the user already owns as many organizations as allowed (422), also when the
     *     creations are simultaneous: they take turns per user
     */
    @Transactional
    public OrganizationWithRole create(UUID userId, String name) {
        locks.lock(LockSpace.ORGANIZATIONS_OWNED_BY_USER, userId);
        int limit = limits.organizationsPerUser();
        if (organizations.countActiveWithRole(userId, Role.OWNER) >= limit) {
            throw new QuotaExceededException("You already own " + limit + " organizations, the most allowed.");
        }
        Organization organization = organizations.save(Organization.create(ids.next(), name, clock));
        memberships.save(Membership.of(organization.id(), userId, Role.OWNER, clock));
        // Assigns the version for the ETag of the response
        organizations.flush();
        return new OrganizationWithRole(organization, Role.OWNER);
    }

    /** Only those the user is a member of, with the user's role in each. */
    @Transactional(readOnly = true)
    public Page<OrganizationWithRole> listOf(UUID userId, Pageable pageable) {
        return organizations.findActiveOfMember(userId, pageable);
    }

    @Transactional(readOnly = true)
    public OrganizationWithRole get(UUID userId, UUID organizationId) {
        Organization organization = active(organizationId);
        Role role = access.require(userId, organization.id(), Permission.ORGANIZATION_READ);
        return new OrganizationWithRole(organization, role);
    }

    /**
     * @param name null to keep it
     * @param ifMatch the {@code If-Match} header, if the client sent one
     */
    @Transactional
    public OrganizationWithRole rename(
            UUID userId, UUID organizationId, @Nullable String name, @Nullable String ifMatch) {
        Organization organization = active(organizationId);
        Role role = access.require(userId, organization.id(), Permission.ORGANIZATION_UPDATE);
        ETags.requireMatch(ifMatch, organization.savedVersion());
        if (name != null) {
            organization.rename(name, clock);
            // Fails here on a concurrent change (@Version), and gives the response its new version
            organizations.flush();
        }
        return new OrganizationWithRole(organization, role);
    }

    /**
     * Logical: it disappears for everyone, its members included, and so do its projects, in the same transaction.
     * The row stays locked ({@code FOR UPDATE}) until the end, as when a project is created: none can be born in it
     * meanwhile and outlive it. Authorized before taking the lock, so that outsiders never hold it, and again with it
     * taken.
     */
    @Transactional
    public void delete(UUID userId, UUID organizationId) {
        access.require(userId, organizationId, Permission.ORGANIZATION_DELETE);
        Organization organization = organizations
                .findActiveByIdForUpdate(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("organization", organizationId));
        access.require(userId, organization.id(), Permission.ORGANIZATION_DELETE);
        organization.delete(clock);
        projects.deleteAllOf(organization.id(), userId);
        events.publishEvent(new OrganizationDeleted(organization.id(), clock.instant()));
    }

    private Organization active(UUID organizationId) {
        return organizations
                .findByIdAndDeletedAtIsNull(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("organization", organizationId));
    }
}
