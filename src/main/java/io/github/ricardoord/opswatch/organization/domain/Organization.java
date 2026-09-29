package io.github.ricardoord.opswatch.organization.domain;

import io.github.ricardoord.opswatch.shared.text.VisibleText;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The tenant: every business record belongs to exactly one organization. Deleting it is logical: it disappears from
 * every query but stays in the table. See docs/architecture/domain-model.md#organization.
 */
@Entity
@Table(name = "organizations")
public class Organization {

    public static final int NAME_MAX_LENGTH = 100;

    @Id
    private UUID id;

    private String name;

    private Instant createdAt;

    private Instant updatedAt;

    private @Nullable Instant deletedAt;

    /** Null until the first save: with the id assigned up front, this is how Spring Data knows the entity is new. */
    @Version
    private @Nullable Long version;

    /** For JPA, which populates the fields. */
    protected Organization() {}

    private Organization(UUID id, String name, Instant now) {
        this.id = id;
        this.name = name;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * @throws IllegalArgumentException if the name breaks an invariant (the request validation should have caught it)
     */
    public static Organization create(UUID id, String name, Clock clock) {
        return new Organization(id, validName(name), now(clock));
    }

    /**
     * @throws IllegalArgumentException if the name breaks an invariant (the request validation should have caught it)
     */
    public void rename(String name, Clock clock) {
        String cleanName = validName(name);
        if (!cleanName.equals(this.name)) {
            this.name = cleanName;
            this.updatedAt = now(clock);
        }
    }

    /** Deleting what is already deleted changes nothing: the first deletion time stays. */
    public void delete(Clock clock) {
        if (deletedAt == null) {
            Instant now = now(clock);
            this.deletedAt = now;
            this.updatedAt = now;
        }
    }

    /** Surrounding spaces are a typing slip; names do not have to be unique. */
    private static String validName(String name) {
        String cleanName = name.strip();
        int length = cleanName.codePointCount(0, cleanName.length());
        if (length < 1 || length > NAME_MAX_LENGTH || !VisibleText.isValid(cleanName)) {
            throw new IllegalArgumentException("Invalid organization name");
        }
        return cleanName;
    }

    /** PostgreSQL keeps microseconds: truncating here makes the returned value match what a later read returns. */
    private static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * The version of the saved row, for {@code ETag} and {@code If-Match}. Not called {@code version()}: Spring Data
     * would read it to tell whether the entity is new, and it throws before the first save.
     */
    public long savedVersion() {
        return Objects.requireNonNull(version, "The organization has not been saved yet");
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof Organization organization && id.equals(organization.id()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Organization[id=" + id + "]";
    }
}
