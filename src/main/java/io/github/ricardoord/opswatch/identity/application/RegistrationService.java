package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.identity.domain.UserRepository;
import io.github.ricardoord.opswatch.shared.error.ConflictException;
import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import java.time.Clock;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Creates accounts. Not transactional on purpose: bcrypt takes about 250 ms at cost 12, and hashing inside a
 * transaction would hold a database connection for that long. The single write runs in the repository's own
 * transaction.
 */
@Service
public class RegistrationService {

    static final String EMAIL_TAKEN = "An account with this email address already exists.";
    private static final String EMAIL_UNIQUE_INDEX = "ux_users_email";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final IdGenerator ids;
    private final Clock clock;

    public RegistrationService(UserRepository users, PasswordEncoder passwordEncoder, IdGenerator ids, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @param password already validated against {@link io.github.ricardoord.opswatch.identity.domain.PasswordRules}
     * @throws ConflictException if the email is already registered, whatever its case
     */
    public User register(String email, String displayName, String password) {
        String normalizedEmail = User.normalizeEmail(email);
        // Saves a useless bcrypt hash in the common case; the unique index settles simultaneous registrations
        if (users.existsByEmail(normalizedEmail)) {
            throw new ConflictException(EMAIL_TAKEN);
        }
        String passwordHash = passwordEncoder.encode(password);
        User user = User.register(ids.next(), normalizedEmail, displayName, passwordHash, clock);
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            if (EMAIL_UNIQUE_INDEX.equals(violatedConstraint(ex))) {
                throw new ConflictException(EMAIL_TAKEN);
            }
            throw ex;
        }
    }

    private static @Nullable String violatedConstraint(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
