package io.github.ricardoord.opswatch.identity.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.identity.domain.UserRepository;
import io.github.ricardoord.opswatch.identity.security.AccessToken;
import io.github.ricardoord.opswatch.identity.security.AccessTokenIssuer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * What keeps the response time from revealing which emails exist: every attempt costs exactly one bcrypt check, and
 * the decoy hash has the cost of the real ones. Measured end to end in {@code LoginApiIT}.
 */
class AuthenticationServiceTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int COST = 5;

    private final UserRepository users = mock(UserRepository.class);
    private final AccessTokenIssuer tokens = mock(AccessTokenIssuer.class);
    private final CountingPasswordEncoder passwordEncoder = new CountingPasswordEncoder();
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final AuthenticationService service =
            new AuthenticationService(users, passwordEncoder, tokens, refreshTokens);

    private final User user =
            aUser().withPasswordHash(passwordEncoder.encode(PASSWORD)).build();
    private final AccessToken token = new AccessToken("signed", Duration.ofMinutes(15));
    private final IssuedRefreshToken refreshToken =
            new IssuedRefreshToken("opaque", Instant.parse("2026-10-12T10:00:00Z"));

    @Test
    void opensASessionForTheRightPasswordWhateverTheCaseOfTheEmail() {
        given(users.findByEmail(user.email())).willReturn(Optional.of(user));
        given(tokens.issue(user.id())).willReturn(token);
        given(refreshTokens.open(user.id())).willReturn(refreshToken);

        assertThat(service.login("  " + user.email().toUpperCase(Locale.ROOT) + " ", PASSWORD, "203.0.113.7"))
                .isEqualTo(new SessionTokens(token, refreshToken));
    }

    @Test
    void checksAnUnknownEmailAgainstADecoyHashOfTheRealCost() {
        given(users.findByEmail(anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("nobody@example.com", PASSWORD, "203.0.113.7"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(passwordEncoder.checkedHashes)
                .singleElement()
                .asString()
                .startsWith("{bcrypt}$2a$0" + COST + "$")
                .isNotEqualTo(user.passwordHash());
    }

    @Test
    void checksAWrongPasswordOnceAgainstTheUsersHash() {
        given(users.findByEmail(user.email())).willReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(user.email(), "wrong password", "203.0.113.7"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(passwordEncoder.checkedHashes).containsExactly(user.passwordHash());
    }

    @Test
    void rejectsADisabledAccountEvenWithTheRightPassword() {
        User disabled = aUser().withPasswordHash(passwordEncoder.encode(PASSWORD))
                .disabled()
                .build();
        given(users.findByEmail(disabled.email())).willReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.login(disabled.email(), PASSWORD, "203.0.113.7"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(InvalidCredentialsException.DETAIL);

        assertThat(passwordEncoder.checkedHashes).hasSize(1);
        verify(tokens, never()).issue(any());
        verify(refreshTokens, never()).open(any());
    }

    @Test
    void skipsBcryptForAPasswordNoAccountCanHaveWhateverTheEmail() {
        String seventyThreeBytes = "a".repeat(73);
        given(users.findByEmail(user.email())).willReturn(Optional.of(user));
        given(users.findByEmail("nobody@example.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(user.email(), seventyThreeBytes, "203.0.113.7"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.login("nobody@example.com", seventyThreeBytes, "203.0.113.7"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(passwordEncoder.checkedHashes).isEmpty();
    }

    /** The production encoder, recording which hash each check compares against. */
    private static final class CountingPasswordEncoder implements PasswordEncoder {

        private final PasswordEncoder delegate =
                new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(COST)));
        private final List<String> checkedHashes = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            checkedHashes.add(encodedPassword);
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
