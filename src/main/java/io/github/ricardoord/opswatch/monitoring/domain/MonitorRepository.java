package io.github.ricardoord.opswatch.monitoring.domain;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Every business query filters out the deleted monitors explicitly (docs/database/database-design.md). */
public interface MonitorRepository extends JpaRepository<Monitor, UUID> {

    /** Name of the unique index of names per project, which settles simultaneous creations and renames. */
    String UNIQUE_NAME_INDEX = "ux_monitors_project_name";

    /** The escape character of {@link #findActiveOfProject}'s name pattern. */
    char LIKE_ESCAPE = '\\';

    Optional<Monitor> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Locks the row ({@code SELECT … FOR UPDATE}) and reads it as last committed: a deletion that waited here for a
     * {@code PATCH} sees the version that {@code PATCH} wrote, and does not fail on it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Monitor m WHERE m.id = :id AND m.deletedAt IS NULL")
    Optional<Monitor> findActiveByIdForUpdate(UUID id);

    /**
     * Ids and not entities: whoever loads each one afterwards, locked, must not get a stale copy from the persistence
     * context.
     */
    @Query("SELECT m.id FROM Monitor m WHERE m.projectId = :projectId AND m.deletedAt IS NULL ORDER BY m.id")
    List<UUID> findActiveIdsOfProject(UUID projectId);

    /** Paused ones included: the quota counts every monitor not deleted. */
    long countByOrganizationIdAndDeletedAtIsNull(UUID organizationId);

    /** Compares with PostgreSQL's {@code lower}, the same function as the unique index. */
    @Query("""
            SELECT count(m) > 0 FROM Monitor m
            WHERE m.projectId = :projectId AND lower(m.name) = lower(:name) AND m.deletedAt IS NULL""")
    boolean existsActiveName(UUID projectId, String name);

    /**
     * The project is a required argument: a listing can never cross projects. Sorts by monitor properties, or by
     * {@code s.status} for the state.
     *
     * @param statuses every status for no filter
     * @param namePattern for {@code LIKE} against the name, whatever the case, with {@link #LIKE_ESCAPE} before a
     *     literal {@code %}, {@code _} or escape character; {@code %} for no filter
     */
    @Query(value = """
            SELECT new io.github.ricardoord.opswatch.monitoring.domain.MonitorWithState(m, s)
            FROM Monitor m JOIN MonitorState s ON s.monitorId = m.id
            WHERE m.projectId = :projectId AND m.deletedAt IS NULL AND s.status IN :statuses
                AND lower(m.name) LIKE lower(:namePattern) ESCAPE '\\'""", countQuery = """
            SELECT count(m) FROM Monitor m JOIN MonitorState s ON s.monitorId = m.id
            WHERE m.projectId = :projectId AND m.deletedAt IS NULL AND s.status IN :statuses
                AND lower(m.name) LIKE lower(:namePattern) ESCAPE '\\'""")
    Page<MonitorWithState> findActiveOfProject(
            UUID projectId, Collection<MonitorStatus> statuses, String namePattern, Pageable pageable);

    /** Only the statuses that have some monitor. */
    @Query("""
            SELECT new io.github.ricardoord.opswatch.monitoring.domain.StatusCount(s.status, count(m))
            FROM Monitor m JOIN MonitorState s ON s.monitorId = m.id
            WHERE m.projectId = :projectId AND m.deletedAt IS NULL
            GROUP BY s.status""")
    List<StatusCount> countActiveByStatus(UUID projectId);
}
