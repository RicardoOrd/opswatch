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
 * A group of monitors inside one organization, which it never leaves. Deleting it is logical: it disappears from every
 * query but stays in the table, so that the history of its monitors survives. See
 * docs/architecture/domain-model.md#project.
 */
@Entity
@Table(name = "projects")
public class Project {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int DESCRIPTION_MAX_LENGTH = 500;

    @Id
    private UUID id;

    private UUID organizationId;

    private String name;

    private @Nullable String description;

    private Instant createdAt;

    private Instant updatedAt;

    private @Nullable Instant deletedAt;

    /** Null until the first save: with the id assigned up front, this is how Spring Data knows the entity is new. */
    @Version
    private @Nullable Long version;

    /** For JPA, which populates the fields. */
    protected Project() {}

    private Project(UUID id, UUID organizationId, String name, @Nullable String description, Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.description = description;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * @param description null for none
     * @throws IllegalArgumentException if the name or the description breaks an invariant (the request validation
     *     should have caught it)
     */
    public static Project create(UUID id, UUID organizationId, String name, @Nullable String description, Clock clock) {
        return new Project(id, organizationId, validName(name), validDescription(description), now(clock));
    }

    /**
     * Unique in the organization whatever the case: the database index has the last word.
     *
     * @throws IllegalArgumentException if the name breaks an invariant (the request validation should have caught it)
     */
    public void rename(String name, Clock clock) {
        String cleanName = validName(name);
        if (!cleanName.equals(this.name)) {
            this.name = cleanName;
            this.updatedAt = now(clock);
        }
    }

    /**
     * @param description null to remove it
     * @throws IllegalArgumentException if the description breaks an invariant (the request validation should have
     *     caught it)
     */
    public void describe(@Nullable String description, Clock clock) {
        String cleanDescription = validDescription(description);
        if (!Objects.equals(cleanDescription, this.description)) {
            this.description = cleanDescription;
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

    /** Surrounding spaces are a typing slip. */
    private static String validName(String name) {
        String cleanName = name.strip();
        if (!fits(cleanName, 1, NAME_MAX_LENGTH)) {
            throw new IllegalArgumentException("Invalid project name");
        }
        return cleanName;
    }

    /** Other members read it too: the same characters as a name. Blank means none. */
    private static @Nullable String validDescription(@Nullable String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String cleanDescription = description.strip();
        if (!fits(cleanDescription, 1, DESCRIPTION_MAX_LENGTH)) {
            throw new IllegalArgumentException("Invalid project description");
        }
        return cleanDescription;
    }

    private static boolean fits(String text, int minLength, int maxLength) {
        int length = text.codePointCount(0, text.length());
        return length >= minLength && length <= maxLength && VisibleText.isValid(text);
    }

    /** PostgreSQL keeps microseconds: truncating here makes the returned value match what a later read returns. */
    private static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public String name() {
        return name;
    }

    public @Nullable String description() {
        return description;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * The version of the saved row, for {@code ETag} and {@code If-Match}. Not called {@code version()}: Spring Data
     * would read it to tell whether the entity is new, and it throws before the first save.
     */
    public long savedVersion() {
        return Objects.requireNonNull(version, "The project has not been saved yet");
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof Project project && id.equals(project.id()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Project[id=" + id + "]";
    }
}
