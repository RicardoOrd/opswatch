package io.github.ricardoord.opswatch.organization.domain;

import io.github.ricardoord.opswatch.organization.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, Membership.Key> {

    /** Empty if the user is not a member, or if the organization does not exist or is deleted. */
    @Query("""
            SELECT m.role FROM Membership m JOIN Organization o ON o.id = m.organizationId
            WHERE m.organizationId = :organizationId AND m.userId = :userId AND o.deletedAt IS NULL""")
    Optional<Role> findActiveRole(UUID organizationId, UUID userId);
}
