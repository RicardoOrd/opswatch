package io.github.ricardoord.opswatch.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * One refresh token, stored as the SHA-256 of its value. Tokens form a family: the login opens it, and each refresh
 * spends the current token on a successor in the same family (docs/security/security-architecture.md). A spent token
 * that comes back reveals a copy, and the whole family is revoked.
 *
 * <p>The rules live here, apart from the repository, so they can be tested without a database. Serializing
 * concurrent rotations of the same token is the caller's job ({@code SELECT … FOR UPDATE}).
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken implements Persistable<UUID> {

    @Id
    private UUID id;

    private UUID userId;

    private UUID familyId;

    private byte[] tokenHash;

    private Instant issuedAt;

    private Instant expiresAt;

    private Instant familyExpiresAt;

    private @Nullable Instant revokedAt;

    @Enumerated(EnumType.STRING)
    private @Nullable RevocationReason revocationReason;

    private @Nullable UUID replacedById;

    /** The table has no version column: this is how Spring Data knows to insert without a {@code SELECT} first. */
    @Transient
    private boolean isNew;

    /** For JPA, which populates the fields. */
    protected RefreshToken() {}

    private RefreshToken(
            UUID id,
            UUID userId,
            UUID familyId,
            byte[] tokenHash,
            Instant issuedAt,
            Instant expiresAt,
            Instant familyExpiresAt) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash.clone();
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.familyExpiresAt = familyExpiresAt;
        this.isNew = true;
    }

    /**
     * The first token of a new family, at login. A family is named after its first token and ends
     * {@code familyMaxTtl} after it, however often it rotates.
     */
    public static RefreshToken openFamily(
            UUID id, UUID userId, byte[] tokenHash, Instant now, Duration ttl, Duration familyMaxTtl) {
        Instant familyExpiresAt = now.plus(familyMaxTtl);
        return new RefreshToken(
                id, userId, id, tokenHash, now, earliest(now.plus(ttl), familyExpiresAt), familyExpiresAt);
    }

    /**
     * Spends this token on its successor in the same family, which never outlives the family.
     *
     * @throws IllegalStateException if this token is revoked or expired
     */
    public RefreshToken rotate(UUID nextId, byte[] nextTokenHash, Instant now, Duration ttl) {
        if (!isActive(now)) {
            throw new IllegalStateException("Only an active refresh token can be rotated");
        }
        revokedAt = now;
        revocationReason = RevocationReason.ROTATED;
        replacedById = nextId;
        return new RefreshToken(
                nextId,
                userId,
                familyId,
                nextTokenHash,
                now,
                earliest(now.plus(ttl), familyExpiresAt),
                familyExpiresAt);
    }

    /** Neither revoked nor expired. */
    public boolean isActive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /** Already spent on a successor. Presented again, it means reuse, whether or not it has expired since. */
    public boolean wasRotated() {
        return revocationReason == RevocationReason.ROTATED;
    }

    private static Instant earliest(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        isNew = false;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID familyId() {
        return familyId;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant familyExpiresAt() {
        return familyExpiresAt;
    }

    public @Nullable Instant revokedAt() {
        return revokedAt;
    }

    public @Nullable RevocationReason revocationReason() {
        return revocationReason;
    }

    public @Nullable UUID replacedById() {
        return replacedById;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof RefreshToken token && id.equals(token.id()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Never includes the hash. */
    @Override
    public String toString() {
        return "RefreshToken[id=" + id + ", familyId=" + familyId + ", revocationReason=" + revocationReason + "]";
    }
}
