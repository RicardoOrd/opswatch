package io.github.ricardoord.opswatch.identity.domain;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserTest {

    @Test
    void registersAnActiveUser() {
        User user = aUser().build();

        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.createdAt()).isEqualTo(UserBuilder.REGISTERED_AT);
    }

    @Test
    void normalizesTheEmailToLowerCaseWithoutSurroundingSpaces() {
        User user = aUser().withEmail("  Ana.Garcia@Example.COM ").build();

        assertThat(user.email()).isEqualTo("ana.garcia@example.com");
    }

    @Test
    void stripsTheDisplayName() {
        assertThat(aUser().withDisplayName("  Ana  ").build().displayName()).isEqualTo("Ana");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no-at-sign", "@example.com", "ána@example.com", "ana @example.com"})
    void rejectsEmailsThatAreNotPrintableAsciiAddresses(String email) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> aUser().withEmail(email).build());
    }

    @Test
    void rejectsEmailsLongerThan254Characters() {
        String local = "a".repeat(64);
        String domain = "b".repeat(254 - 64 - 1 - 4) + ".com";
        assertThat(aUser().withEmail(local + "@" + domain).build().email()).hasSize(254);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> aUser().withEmail("a" + local + "@" + domain).build());
    }

    @Test
    void acceptsDisplayNamesOfUpTo100Characters() {
        // 100 emojis are 200 UTF-16 units but 100 characters for PostgreSQL's char_length
        assertThat(aUser().withDisplayName("😀".repeat(100)).build().displayName())
                .hasSize(200);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> aUser().withDisplayName("a".repeat(101)).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"   ", "Ana\u0000", "Ana\nGarcía", "Ana\u2028García", "\u202Egnp.exe"})
    void rejectsBlankDisplayNamesAndOnesWithControlCharacters(String displayName) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> aUser().withDisplayName(displayName).build());
    }

    @Test
    void storesTimesWithThePrecisionOfPostgres() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00.123456789Z"), ZoneOffset.UTC);

        User user = User.register(UUID.randomUUID(), "ana@example.com", "Ana", "{bcrypt}hash", clock);

        assertThat(user.createdAt()).isEqualTo(Instant.parse("2026-09-28T10:00:00.123456Z"));
    }

    @Test
    void usersWithTheSameIdAreEqual() {
        User user = aUser().build();
        User sameId = User.register(user.id(), "other@example.com", "Other", "{bcrypt}hash", Clock.systemUTC());

        assertThat(user).isEqualTo(sameId).hasSameHashCodeAs(sameId);
        assertThat(user).isNotEqualTo(aUser().build());
    }

    @Test
    void toStringNeverShowsThePasswordHash() {
        User user = aUser().build();

        assertThat(user.toString()).doesNotContain(user.passwordHash()).doesNotContain("bcrypt");
    }
}
