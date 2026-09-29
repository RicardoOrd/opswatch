package io.github.ricardoord.opswatch.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A person who can sign in. Invariants mirror the CHECK constraints of the {@code users} table, so a bug in the layers
 * above fails here with a clear message instead of as a constraint violation. See docs/architecture/domain-model.md.
 */
@Entity
@Table(name = "users")
public class User {

    public static final int EMAIL_MAX_LENGTH = 254;
    public static final int DISPLAY_NAME_MAX_LENGTH = 100;

    /**
     * Display names are shown to other members of an organization, so they cannot carry control characters, line or
     * paragraph separators, or bidirectional overrides that would disguise them.
     */
    public static final String DISPLAY_NAME_PATTERN = "[^\\p{Cc}\\p{Zl}\\p{Zp}\\u202A-\\u202E\\u2066-\\u2069]*";

    /** Visible US-ASCII only: lower-casing then means the same in Java and in PostgreSQL's {@code lower()}. */
    public static final String EMAIL_CHARACTERS_PATTERN = "[\\x21-\\x7E]+";

    private static final Pattern DISPLAY_NAME = Pattern.compile(DISPLAY_NAME_PATTERN);
    private static final Pattern EMAIL_CHARACTERS = Pattern.compile(EMAIL_CHARACTERS_PATTERN);

    @Id
    private UUID id;

    private String email;

    private String displayName;

    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private UserStatus status;

    private @Nullable Instant emailVerifiedAt;

    private Instant createdAt;

    private Instant updatedAt;

    /** Null until the first save: with the id assigned up front, this is how Spring Data knows the entity is new. */
    @Version
    private @Nullable Long version;

    /** For JPA, which populates the fields. */
    protected User() {}

    private User(UUID id, String email, String displayName, String passwordHash, Instant now) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.status = UserStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * A new active account. The email is normalized here; the password must already be hashed.
     *
     * @throws IllegalArgumentException if a value breaks an invariant (the request validation should have caught it)
     */
    public static User register(UUID id, String email, String displayName, String passwordHash, Clock clock) {
        String normalizedEmail = normalizeEmail(email);
        requireValidEmail(normalizedEmail);
        String cleanDisplayName = displayName.strip();
        requireValidDisplayName(cleanDisplayName);
        if (passwordHash.isBlank()) {
            throw new IllegalArgumentException("The password hash is required");
        }
        // PostgreSQL keeps microseconds: truncating here makes the returned value match what a later read returns
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return new User(id, normalizedEmail, cleanDisplayName, passwordHash, now);
    }

    /** The form in which emails are stored and compared: without surrounding spaces and in lower case. */
    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static void requireValidEmail(String email) {
        if (email.length() > EMAIL_MAX_LENGTH
                || !EMAIL_CHARACTERS.matcher(email).matches()
                || email.indexOf('@') <= 0) {
            throw new IllegalArgumentException("Invalid email address");
        }
    }

    private static void requireValidDisplayName(String displayName) {
        int length = displayName.codePointCount(0, displayName.length());
        if (length < 1
                || length > DISPLAY_NAME_MAX_LENGTH
                || !DISPLAY_NAME.matcher(displayName).matches()) {
            throw new IllegalArgumentException("Invalid display name");
        }
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public UserStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof User user && id.equals(user.id()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Never includes the password hash. */
    @Override
    public String toString() {
        return "User[id=" + id + ", status=" + status + "]";
    }
}
