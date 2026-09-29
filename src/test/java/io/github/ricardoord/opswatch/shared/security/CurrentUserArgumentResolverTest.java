package io.github.ricardoord.opswatch.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentUserArgumentResolverTest {

    @Test
    void readsTheUserIdFromTheSubjectOfTheToken() {
        UUID id = UUID.randomUUID();

        assertThat(CurrentUserArgumentResolver.current(authenticationFor(id.toString())))
                .isEqualTo(new CurrentUser(id));
    }

    @Test
    void rejectsMissingAndAnonymousAuthentication() {
        var anonymous =
                new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ANON"));

        assertThatThrownBy(() -> CurrentUserArgumentResolver.current(null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> CurrentUserArgumentResolver.current(anonymous))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void rejectsASubjectThatIsNotAUserId() {
        assertThatThrownBy(() -> CurrentUserArgumentResolver.current(authenticationFor("admin")))
                .isInstanceOf(BadCredentialsException.class);
    }

    private static JwtAuthenticationToken authenticationFor(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.EPOCH)
                .build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
    }
}
