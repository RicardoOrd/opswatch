package io.github.ricardoord.opswatch.identity.domain;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.aUser;
import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.ricardoord.opswatch.IntegrationTest;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class UserRepositoryIT {

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void insertsANewUserWithoutMergingIt() {
        User user = aUser().build();

        // persist() keeps the instance; merge() would return a copy after a SELECT by id
        assertThat(users.saveAndFlush(user)).isSameAs(user);
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, user.id()))
                .isZero();
    }

    @Test
    void readsBackWhatItStored() {
        User user = users.saveAndFlush(aUser().withDisplayName("Ana García").build());

        User stored = users.findById(user.id()).orElseThrow();

        assertThat(stored.email()).isEqualTo(user.email());
        assertThat(stored.displayName()).isEqualTo("Ana García");
        assertThat(stored.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(stored.createdAt()).isEqualTo(user.createdAt());
    }

    @Test
    void theUniqueIndexRejectsARepeatedEmail() {
        String email = uniqueEmail();
        users.saveAndFlush(aUser().withEmail(email).build());

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> users.saveAndFlush(aUser().withEmail(email).build()));
    }

    @Test
    void findsEmailsInTheirNormalizedForm() {
        String email = uniqueEmail();
        users.saveAndFlush(aUser().withEmail(email.toUpperCase(Locale.ROOT)).build());

        assertThat(users.existsByEmail(email)).isTrue();
        assertThat(users.existsByEmail(uniqueEmail())).isFalse();
    }
}
