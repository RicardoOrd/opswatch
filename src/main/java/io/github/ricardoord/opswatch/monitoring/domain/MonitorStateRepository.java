package io.github.ricardoord.opswatch.monitoring.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MonitorStateRepository extends JpaRepository<MonitorState, UUID> {

    /**
     * Locks the row until the transaction ends: every write to the state takes it first (rescheduling, and from OW-044
     * pausing, resuming and deleting), so none of them builds on a state another one is changing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MonitorState s WHERE s.monitorId = :monitorId")
    Optional<MonitorState> findByIdForUpdate(UUID monitorId);

    /**
     * Deletes up to {@code batch} states of deleted monitors that have no checks left: the checks go first
     * (docs/database/data-retention.md#job-de-purga-de-checks). The row of {@code monitors} stays, for the incidents that
     * will refer to it. {@code SKIP LOCKED}: a result being recorded holds the row, and its monitor waits for the next
     * purge. Run it in a short transaction of its own.
     *
     * @return how many it deleted; fewer than {@code batch} when nothing is left
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM monitor_state
            WHERE monitor_id = ANY (ARRAY(
                SELECT s.monitor_id FROM monitor_state s
                JOIN monitors m ON m.id = s.monitor_id
                WHERE m.deleted_at IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM monitor_checks c WHERE c.monitor_id = s.monitor_id)
                LIMIT :batch
                FOR UPDATE OF s SKIP LOCKED))""")
    int deleteOfDeletedMonitorsWithoutChecks(int batch);
}
