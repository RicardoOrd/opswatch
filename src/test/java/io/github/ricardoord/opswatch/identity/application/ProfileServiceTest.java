package io.github.ricardoord.opswatch.identity.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.ricardoord.opswatch.identity.domain.RevocationReason;
import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.identity.domain.UserRepository;
import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;

/** The rules of the password change. Through the API, with PostgreSQL and the real sessions, in PasswordChangeApiIT. */
class ProfileServiceTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String NEW_PASSWORD = "another long passphrase";
    private static final String ADDRESS = "203.0.113.7";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final ProfileService service = new ProfileService(
            users,
            passwordEncoder,
            refreshTokens,
            TransactionOperations.withoutTransaction(),
            Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC));

    private final User user =
            aUser().withPasswordHash(passwordEncoder.encode(PASSWORD)).build();

    @Test
    void changesThePasswordAndEndsEverySessionOfTheUser() {
        given(users.findById(user.id())).willReturn(Optional.of(user));

        service.changePassword(user.id(), PASSWORD, NEW_PASSWORD, ADDRESS);

        assertThat(passwordEncoder.matches(NEW_PASSWORD, user.passwordHash())).isTrue();
        verify(refreshTokens).revokeAllOf(user.id(), RevocationReason.PASSWORD_CHANGED);
    }

    @Test
    void aWrongCurrentPasswordIsAnInvalidFieldAndChangesNothing() {
        String hash = user.passwordHash();
        given(users.findById(user.id())).willReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword(user.id(), "wrong password!", NEW_PASSWORD, ADDRESS))
                .isInstanceOf(InvalidFieldException.class)
                .hasMessage(ProfileService.WRONG_CURRENT_PASSWORD);
        // Longer than bcrypt accepts, so no account can have it
        assertThatThrownBy(() -> service.changePassword(user.id(), PASSWORD + "x".repeat(60), NEW_PASSWORD, ADDRESS))
                .isInstanceOf(InvalidFieldException.class);

        assertThat(user.passwordHash()).isEqualTo(hash);
        verify(refreshTokens, never()).revokeAllOf(any(), any());
    }

    @Test
    void aChangeThatGotInFirstIsNotOverwritten() {
        // Read once to check the current password and again to write, after another change replaced it
        User changedMeanwhile = aUser().withPasswordHash(passwordEncoder.encode("someone else's new one"))
                .build();
        given(users.findById(user.id())).willReturn(Optional.of(user)).willReturn(Optional.of(changedMeanwhile));
        String hashOfTheOtherChange = changedMeanwhile.passwordHash();

        assertThatThrownBy(() -> service.changePassword(user.id(), PASSWORD, NEW_PASSWORD, ADDRESS))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(changedMeanwhile.passwordHash()).isEqualTo(hashOfTheOtherChange);
        verify(refreshTokens, never()).revokeAllOf(any(), any());
    }
}
