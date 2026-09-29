package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.identity.domain.PasswordRules;
import io.github.ricardoord.opswatch.identity.domain.RevocationReason;
import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.identity.domain.UserRepository;
import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The account of the signed-in user: read it, rename it and change its password. The email does not change in V1:
 * changing it needs verification (OW-037).
 */
@Service
public class ProfileService {

    static final String WRONG_CURRENT_PASSWORD = "is not the current password";

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final TransactionOperations transactions;
    private final Clock clock;

    public ProfileService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokens,
            TransactionOperations transactions,
            Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public User get(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("user", userId));
    }

    @Transactional
    public User rename(UUID userId, String displayName) {
        User user = get(userId);
        user.rename(displayName, clock);
        return user;
    }

    /**
     * Replaces the password and ends every session of the user, in one transaction. The access tokens already issued
     * stay valid until they expire (ADR-004).
     *
     * <p>Both bcrypt operations run outside the transaction, like registration and login, so they do not hold a
     * database connection.
     *
     * @param newPassword already validated against {@link PasswordRules}
     * @param clientAddress for the security log only
     * @throws InvalidFieldException on {@code currentPassword} if it is not the current password
     * @throws OptimisticLockingFailureException if another change of the password got in first
     */
    public void changePassword(UUID userId, String currentPassword, String newPassword, String clientAddress) {
        String checkedHash = get(userId).passwordHash();
        // A password longer than bcrypt accepts cannot be anyone's (registration rejects it)
        if (!PasswordRules.fitsInBcrypt(currentPassword) || !passwordEncoder.matches(currentPassword, checkedHash)) {
            logEvent("auth.password.change_failed", userId, clientAddress).log("Password change failed");
            throw new InvalidFieldException("currentPassword", "incorrect-password", WRONG_CURRENT_PASSWORD);
        }
        String newHash = passwordEncoder.encode(newPassword);

        Integer revoked = transactions.execute(status -> {
            User user = get(userId);
            // Checked against the password of the request: a change in between would leave it unverified. Two
            // changes at the same time read the same version, and the second commit fails on @Version
            if (!user.passwordHash().equals(checkedHash)) {
                throw new OptimisticLockingFailureException("The password changed while this request was checking it");
            }
            user.changePassword(newHash, clock);
            return refreshTokens.revokeAllOf(userId, RevocationReason.PASSWORD_CHANGED);
        });
        logEvent("auth.password.changed", userId, clientAddress)
                .addKeyValue("sessions.revoked", revoked)
                .log("Password changed");
    }

    /** A security event (docs/devops/observability.md#eventos-de-seguridad), never with a password. */
    private static LoggingEventBuilder logEvent(String action, UUID userId, String clientAddress) {
        return log.atInfo()
                .addKeyValue("event.category", "security")
                .addKeyValue("event.action", action)
                .addKeyValue("user.id", userId)
                .addKeyValue("client.address", clientAddress);
    }
}
