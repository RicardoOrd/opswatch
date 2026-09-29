package io.github.ricardoord.opswatch.identity.domain;

import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Test data for {@link User} (docs/testing/testing-strategy.md#convenciones). Every built user has a fresh id and a
 * unique email, so tests sharing a database never collide.
 */
public final class UserBuilder {

    public static final Instant REGISTERED_AT = Instant.parse("2026-09-28T10:00:00Z");

    private static final Clock CLOCK = Clock.fixed(REGISTERED_AT, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK);

    private UUID id = IDS.next();
    private String email = uniqueEmail();
    private String displayName = "Ana";
    private String passwordHash = "{bcrypt}$2a$04$not.a.real.hash.only.test.data";

    private UserBuilder() {}

    public static UserBuilder aUser() {
        return new UserBuilder();
    }

    public static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    public UserBuilder withEmail(String email) {
        this.email = email;
        return this;
    }

    public UserBuilder withDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public User build() {
        return User.register(id, email, displayName, passwordHash, CLOCK);
    }
}
