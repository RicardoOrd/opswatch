package io.github.ricardoord.opswatch.organization.domain;

import io.github.ricardoord.opswatch.organization.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /** Namespace of the advisory locks that serialize the quota of each user (two-key form of PostgreSQL). */
    int QUOTA_LOCK_NAMESPACE = 1;

    Optional<Organization> findByIdAndDeletedAtIsNull(UUID id);

    /** Only the organizations the user is a member of: the listing can never show another tenant. */
    @Query(value = """
            SELECT new io.github.ricardoord.opswatch.organization.domain.OrganizationWithRole(o, m.role)
            FROM Organization o JOIN Membership m ON m.organizationId = o.id
            WHERE m.userId = :userId AND o.deletedAt IS NULL""", countQuery = """
            SELECT count(o) FROM Organization o JOIN Membership m ON m.organizationId = o.id
            WHERE m.userId = :userId AND o.deletedAt IS NULL""")
    Page<OrganizationWithRole> findActiveOfMember(UUID userId, Pageable pageable);

    @Query("""
            SELECT count(o) FROM Organization o JOIN Membership m ON m.organizationId = o.id
            WHERE m.userId = :userId AND m.role = :role AND o.deletedAt IS NULL""")
    long countActiveWithRole(UUID userId, Role role);

    /**
     * Waits for the other transactions of the same user that hold this lock, until the current one ends. Checking a
     * quota and inserting under it does not overcount with simultaneous requests.
     */
    @Query(nativeQuery = true, value = """
            SELECT 1 FROM pg_advisory_xact_lock(
                CAST(:namespace AS integer), hashtext(CAST(:userId AS text)))""")
    int lockQuotaOf(int namespace, UUID userId);
}
