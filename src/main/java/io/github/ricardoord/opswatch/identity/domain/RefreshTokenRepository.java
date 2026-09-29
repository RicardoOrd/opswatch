package io.github.ricardoord.opswatch.identity.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Locks the row until the transaction ends, so two refreshes with the same token take turns: the second one finds
     * it already rotated.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(byte[] tokenHash);

    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    /** @return how many tokens it revoked; the ones already revoked keep their reason */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.revokedAt = :now, t.revocationReason = :reason
            WHERE t.familyId = :familyId AND t.revokedAt IS NULL""")
    int revokeFamily(UUID familyId, RevocationReason reason, Instant now);

    /** @return how many tokens it revoked; the ones already revoked keep their reason */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.revokedAt = :now, t.revocationReason = :reason
            WHERE t.userId = :userId AND t.revokedAt IS NULL""")
    int revokeAllOfUser(UUID userId, RevocationReason reason, Instant now);

    /**
     * Deletes up to {@code batchSize} tokens that stopped being useful before {@code cutoff}, in its own short
     * transaction (docs/database/data-retention.md). A rotated token stays until it expires: until then, presenting it
     * again still reveals reuse. Other revocations end the whole family, so there is nothing left to detect.
     *
     * @return how many it deleted
     */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM refresh_tokens
            WHERE id = ANY (ARRAY(
                SELECT id FROM refresh_tokens
                WHERE expires_at < :cutoff
                   OR (revoked_at < :cutoff AND revocation_reason <> 'ROTATED')
                LIMIT :batchSize))""")
    int deleteSpentBefore(Instant cutoff, int batchSize);
}
