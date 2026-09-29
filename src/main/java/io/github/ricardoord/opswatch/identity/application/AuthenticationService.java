package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.identity.domain.PasswordRules;
import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.identity.domain.UserRepository;
import io.github.ricardoord.opswatch.identity.domain.UserStatus;
import io.github.ricardoord.opswatch.identity.security.AccessToken;
import io.github.ricardoord.opswatch.identity.security.AccessTokenIssuer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Email and password login (docs/security/security-architecture.md#protección-del-login). Not transactional, for the
 * same reason as registration: bcrypt must not hold a database connection.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer tokens;

    /**
     * Compared against when the email does not exist, so that case costs one bcrypt check like a wrong password and
     * the response time does not reveal which emails are registered. Hashed at startup with the configured cost.
     */
    private final String decoyHash;

    public AuthenticationService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenIssuer tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.decoyHash = passwordEncoder.encode(randomPassword());
    }

    /**
     * @param clientAddress for the security log only
     * @throws InvalidCredentialsException for an unknown email, a wrong password or a disabled account alike
     */
    public AccessToken login(String email, String password, String clientAddress) {
        String normalizedEmail = User.normalizeEmail(email);
        Optional<User> user = users.findByEmail(normalizedEmail);

        // Exactly one bcrypt comparison whatever the case. A password longer than bcrypt accepts cannot be anyone's
        // (registration rejects it), and skipping it takes the same time for every email
        boolean passwordMatches = PasswordRules.fitsInBcrypt(password)
                && passwordEncoder.matches(
                        password, user.map(User::passwordHash).orElse(decoyHash));

        String failure = failure(user, passwordMatches);
        if (failure != null) {
            logFailure(normalizedEmail, clientAddress, failure);
            throw new InvalidCredentialsException();
        }
        return tokens.issue(user.orElseThrow().id());
    }

    private static @Nullable String failure(Optional<User> user, boolean passwordMatches) {
        if (user.isEmpty()) {
            return "unknown-email";
        }
        if (!passwordMatches) {
            return "wrong-password";
        }
        return user.get().status() == UserStatus.ACTIVE ? null : "disabled";
    }

    /** A security event (docs/devops/observability.md#eventos-de-seguridad): the email only as a hash. */
    private static void logFailure(String normalizedEmail, String clientAddress, String reason) {
        log.atInfo()
                .addKeyValue("event.category", "security")
                .addKeyValue("event.action", "auth.login.failed")
                .addKeyValue("event.reason", reason)
                .addKeyValue("user.email.hash", emailHash(normalizedEmail))
                .addKeyValue("client.address", clientAddress)
                .log("Login failed: {}", reason);
    }

    /** Enough to link attempts against the same account without writing the email down. */
    private static String emailHash(String normalizedEmail) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(normalizedEmail.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
