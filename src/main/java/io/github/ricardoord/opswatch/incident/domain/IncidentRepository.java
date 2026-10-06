package io.github.ricardoord.opswatch.incident.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    /**
     * The active incident of a monitor, locked until the transaction ends: the resolution and the acknowledgement take
     * this lock, always after the one on the state of the monitor when both are held, so they wait for each other
     * instead of failing (docs/architecture/incident-lifecycle.md#6-concurrencia-y-casos-límite).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT i FROM Incident i
            WHERE i.monitorId = :monitorId
                AND i.status <> io.github.ricardoord.opswatch.incident.domain.IncidentStatus.RESOLVED""")
    Optional<Incident> findActiveByMonitorIdForUpdate(UUID monitorId);

    /**
     * Locked until the transaction ends, the same lock as {@link #findActiveByMonitorIdForUpdate}: an acknowledgement
     * that waited here for a resolution reads the incident as the resolution left it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Incident i WHERE i.id = :id")
    Optional<Incident> findByIdForUpdate(UUID id);
}
