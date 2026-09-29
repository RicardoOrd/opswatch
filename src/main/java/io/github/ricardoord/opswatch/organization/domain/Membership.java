package io.github.ricardoord.opswatch.organization.domain;

import io.github.ricardoord.opswatch.organization.Role;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The role of a user in one organization. The organization and the user are referenced by id, not mapped: they are
 * other aggregates, and the user belongs to another module.
 */
@Entity
@Table(name = "memberships")
@IdClass(Membership.Key.class)
public class Membership {

    @Id
    private UUID organizationId;

    @Id
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private Role role;

    private Instant createdAt;

    private Instant updatedAt;

    /** Null until the first save: with the key assigned up front, this is how Spring Data knows the entity is new. */
    @Version
    private @Nullable Long version;

    /** For JPA, which populates the fields. */
    protected Membership() {}

    private Membership(UUID organizationId, UUID userId, Role role, Instant now) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.role = role;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Membership of(UUID organizationId, UUID userId, Role role, Clock clock) {
        return new Membership(organizationId, userId, role, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID userId() {
        return userId;
    }

    public Role role() {
        return role;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || (other instanceof Membership membership
                        && organizationId.equals(membership.organizationId())
                        && userId.equals(membership.userId()));
    }

    @Override
    public int hashCode() {
        return 31 * organizationId.hashCode() + userId.hashCode();
    }

    @Override
    public String toString() {
        return "Membership[organizationId=" + organizationId + ", userId=" + userId + ", role=" + role + "]";
    }

    /** The primary key: one membership per user and organization. */
    public record Key(UUID organizationId, UUID userId) implements Serializable {}
}
