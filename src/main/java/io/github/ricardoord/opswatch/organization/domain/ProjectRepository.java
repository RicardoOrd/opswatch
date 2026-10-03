package io.github.ricardoord.opswatch.organization.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Every business query filters out the deleted projects explicitly (docs/database/database-design.md). */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** Name of the unique index of names per organization, which settles simultaneous creations and renames. */
    String UNIQUE_NAME_INDEX = "ux_projects_org_name";

    Optional<Project> findByIdAndDeletedAtIsNull(UUID id);

    /** The organization is a required argument: a listing can never cross tenants. */
    Page<Project> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId, Pageable pageable);

    List<Project> findAllByOrganizationIdAndDeletedAtIsNull(UUID organizationId);

    long countByOrganizationIdAndDeletedAtIsNull(UUID organizationId);

    /** Compares with PostgreSQL's {@code lower}, the same function as the unique index. */
    @Query("""
            SELECT count(p) > 0 FROM Project p
            WHERE p.organizationId = :organizationId AND lower(p.name) = lower(:name) AND p.deletedAt IS NULL""")
    boolean existsActiveName(UUID organizationId, String name);

    /**
     * {@code SELECT … FOR SHARE} until the transaction ends: deleting the project waits, so nothing can be added to a
     * project that is being deleted.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT p FROM Project p WHERE p.id = :id AND p.deletedAt IS NULL")
    Optional<Project> findActiveByIdForShare(UUID id);
}
