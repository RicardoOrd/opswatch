package io.github.ricardoord.opswatch.monitoring.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MonitorStateRepository extends JpaRepository<MonitorState, UUID> {

    /**
     * Locks the row until the transaction ends: every write to the state takes it first (rescheduling, and from OW-044
     * pausing, resuming and deleting), so none of them builds on a state another one is changing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MonitorState s WHERE s.monitorId = :monitorId")
    Optional<MonitorState> findByIdForUpdate(UUID monitorId);
}
