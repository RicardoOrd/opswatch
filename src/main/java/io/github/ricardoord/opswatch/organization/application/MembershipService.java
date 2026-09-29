package io.github.ricardoord.opswatch.organization.application;

import io.github.ricardoord.opswatch.identity.UserDirectory;
import io.github.ricardoord.opswatch.identity.UserSummary;
import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.organization.Role;
import io.github.ricardoord.opswatch.organization.domain.Membership;
import io.github.ricardoord.opswatch.organization.domain.MembershipPolicy;
import io.github.ricardoord.opswatch.organization.domain.MembershipRepository;
import io.github.ricardoord.opswatch.organization.domain.OrganizationRepository;
import io.github.ricardoord.opswatch.shared.error.BusinessRuleViolationException;
import io.github.ricardoord.opswatch.shared.error.ConflictException;
import io.github.ricardoord.opswatch.shared.error.PermissionDeniedException;
import io.github.ricardoord.opswatch.shared.error.QuotaExceededException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import io.github.ricardoord.opswatch.shared.web.ETags;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Members and their roles, with the rules of docs/security/authorization-model.md#reglas-que-la-matriz-no-expresa.
 *
 * <p>Every change locks the organization's row first ({@code SELECT … FOR UPDATE}) and only then reads the members and
 * decides: the changes to one organization take turns, and each one is authorized with the roles as they are once it
 * holds the lock, not as they were before another change committed.
 */
@Service
public class MembershipService {

    static final String LAST_OWNER = "An organization needs at least one OWNER.";

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final AccessControl access;
    private final UserDirectory users;
    private final OrganizationLimits limits;
    private final Clock clock;

    public MembershipService(
            OrganizationRepository organizations,
            MembershipRepository memberships,
            AccessControl access,
            UserDirectory users,
            OrganizationLimits limits,
            Clock clock) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.access = access;
        this.users = users;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Member> list(UUID actorId, UUID organizationId, Pageable pageable) {
        access.require(actorId, organizationId, Permission.MEMBER_READ);
        Page<Membership> page = memberships.findByOrganizationId(organizationId, pageable);
        Map<UUID, UserSummary> byId =
                users.findAllById(page.map(Membership::userId).getContent());
        // A membership's user always exists: the foreign key deletes the membership with it
        return page.map(membership -> new Member(membership, byId.get(membership.userId())));
    }

    /**
     * Adds a user who already has an account. Revealing whether an email is registered to whoever may manage members
     * is an accepted risk until invitations arrive (T-06).
     *
     * @throws PermissionDeniedException if the role to give needs a permission the caller lacks (403)
     * @throws ResourceNotFoundException if no account has that email (404)
     * @throws ConflictException if the user is already a member (409)
     * @throws QuotaExceededException if the organization is full (422)
     */
    @Transactional
    public Member add(UUID actorId, UUID organizationId, String email, Role role) {
        lockActive(organizationId);
        // Before looking the email up, so that only members who may add others learn whether it is registered
        access.require(actorId, organizationId, MembershipPolicy.toManage(role));
        UserSummary user = users.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("user with email", email.strip()));
        if (memberships.existsById(new Membership.Key(organizationId, user.id()))) {
            throw new ConflictException("The user is already a member of the organization.");
        }
        int limit = limits.membersPerOrganization();
        if (memberships.countByOrganizationId(organizationId) >= limit) {
            throw new QuotaExceededException("The organization already has " + limit + " members, the most allowed.");
        }
        Membership membership = memberships.saveAndFlush(Membership.of(organizationId, user.id(), role, clock));
        return new Member(membership, user);
    }

    /**
     * The checks go in this order: membership of the caller (404), the member (404), the last {@code OWNER} (409),
     * raising one's own role (403), the permission for the roles involved (403) and {@code If-Match} (412).
     *
     * <p>The last {@code OWNER} goes before the permission on purpose: when two {@code OWNER}s demote each other at
     * once, the second one to get the lock is no longer {@code OWNER}, and the answer that explains why is the
     * invariant. On oneself, going down needs no permission, as leaving does not; going up is never allowed.
     *
     * @param role null to keep it
     */
    @Transactional
    public Member changeRole(
            UUID actorId, UUID organizationId, UUID userId, @Nullable Role role, @Nullable String ifMatch) {
        lockActive(organizationId);
        access.require(actorId, organizationId, Permission.MEMBER_READ);
        Membership member = member(organizationId, userId);
        Role from = member.role();
        if (role != null) {
            if (MembershipPolicy.removesLastOwner(from, role, owners(organizationId))) {
                throw new BusinessRuleViolationException(LAST_OWNER);
            }
            boolean onOneself = actorId.equals(userId);
            if (MembershipPolicy.isSelfPromotion(onOneself, from, role)) {
                throw new PermissionDeniedException("Nobody can raise their own role.");
            }
            if (!onOneself) {
                access.require(actorId, organizationId, MembershipPolicy.toManage(from, role));
            }
        }
        ETags.requireMatch(ifMatch, member.savedVersion());
        if (role != null) {
            member.changeRole(role, clock);
            // Gives the response its new version
            memberships.flush();
        }
        return new Member(member, user(userId));
    }

    /**
     * Removes a member, or lets the caller leave: any member may leave, except the last {@code OWNER}. Removing someone
     * else needs the permission for that member's role.
     */
    @Transactional
    public void remove(UUID actorId, UUID organizationId, UUID userId) {
        lockActive(organizationId);
        access.require(actorId, organizationId, Permission.MEMBER_READ);
        Membership member = member(organizationId, userId);
        if (MembershipPolicy.removesLastOwner(member.role(), null, owners(organizationId))) {
            throw new BusinessRuleViolationException(LAST_OWNER);
        }
        if (!actorId.equals(userId)) {
            access.require(actorId, organizationId, MembershipPolicy.toManage(member.role()));
        }
        memberships.delete(member);
    }

    private void lockActive(UUID organizationId) {
        organizations
                .findActiveByIdForUpdate(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("organization", organizationId));
    }

    private Membership member(UUID organizationId, UUID userId) {
        return memberships
                .findById(new Membership.Key(organizationId, userId))
                .orElseThrow(() -> new ResourceNotFoundException("member", userId));
    }

    private long owners(UUID organizationId) {
        return memberships.countByOrganizationIdAndRole(organizationId, Role.OWNER);
    }

    private UserSummary user(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("user", userId));
    }
}
