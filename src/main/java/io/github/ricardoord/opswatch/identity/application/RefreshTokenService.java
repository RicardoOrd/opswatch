package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.identity.domain.RefreshToken;
import io.github.ricardoord.opswatch.identity.domain.RefreshTokenRepository;
import io.github.ricardoord.opswatch.identity.domain.RevocationReason;
import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.identity.domain.UserRepository;
import io.github.ricardoord.opswatch.identity.domain.UserStatus;
import io.github.ricardoord.opswatch.identity.security.RefreshTokenProperties;
import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh tokens that rotate on every use and detect reuse (docs/security/security-architecture.md, ADR-004).
 * The value is 32 random bytes in Base64URL; only its SHA-256 reaches the database.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final RefreshTokenProperties properties;
    private final IdGenerator ids;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository refreshTokens,
            UserRepository users,
            RefreshTokenProperties properties,
            IdGenerator ids,
            Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
    }

    /** Opens a new family at login. */
    @Transactional
    public IssuedRefreshToken open(UUID userId) {
        String value = newValue();
        RefreshToken token = RefreshToken.openFamily(
                ids.next(), userId, hash(value), now(), properties.ttl(), properties.familyMaxTtl());
        refreshTokens.save(token);
        return new IssuedRefreshToken(value, token.expiresAt());
    }

    /**
     * Spends the presented token on a new one of the same family. The row stays locked until the commit, so of two
     * refreshes with the same token the second finds it rotated: that is reuse, and the whole family goes, including
     * the token the first one just got. The revocations are committed even though the method then throws.
     *
     * @param clientAddress for the security log only
     * @throws InvalidRefreshTokenException for an unknown, expired, revoked or reused token, or a disabled account
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String value, String clientAddress) {
        RefreshToken current =
                refreshTokens.findByTokenHashForUpdate(hash(value)).orElseThrow(InvalidRefreshTokenException::new);
        Instant now = now();

        if (current.wasRotated()) {
            refreshTokens.revokeFamily(current.familyId(), RevocationReason.REUSE_DETECTED, now);
            logReuse(current, clientAddress);
            throw new InvalidRefreshTokenException();
        }
        if (!current.isActive(now)) {
            throw new InvalidRefreshTokenException();
        }
        Optional<User> user = users.findById(current.userId());
        if (user.isEmpty() || user.get().status() != UserStatus.ACTIVE) {
            refreshTokens.revokeFamily(current.familyId(), RevocationReason.USER_DISABLED, now);
            throw new InvalidRefreshTokenException();
        }

        String nextValue = newValue();
        RefreshToken next = current.rotate(ids.next(), hash(nextValue), now, properties.ttl());
        refreshTokens.save(next);
        return new Rotation(current.userId(), new IssuedRefreshToken(nextValue, next.expiresAt()));
    }

    /** Ends the session the token belongs to. An unknown token is ignored: the session is over either way. */
    @Transactional
    public void revokeFamily(String value, RevocationReason reason) {
        refreshTokens
                .findByTokenHash(hash(value))
                .ifPresent(token -> refreshTokens.revokeFamily(token.familyId(), reason, now()));
    }

    /** Ends every session of the user, for instance after a password change (OW-045). */
    @Transactional
    public int revokeAllOf(UUID userId, RevocationReason reason) {
        return refreshTokens.revokeAllOfUser(userId, reason, now());
    }

    /** A rotated token: whose it is and its successor. */
    public record Rotation(UUID userId, IssuedRefreshToken refreshToken) {}

    /** The stored form of a token value. */
    static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }

    private String newValue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** PostgreSQL keeps microseconds: truncating makes the returned expiry match what a later read returns. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** A security event (docs/devops/observability.md#eventos-de-seguridad): someone holds a copy of the token. */
    private static void logReuse(RefreshToken token, String clientAddress) {
        log.atWarn()
                .addKeyValue("event.category", "security")
                .addKeyValue("event.action", "auth.refresh.reuse_detected")
                .addKeyValue("user.id", token.userId())
                .addKeyValue("session.family.id", token.familyId())
                .addKeyValue("client.address", clientAddress)
                .log("Refresh token reuse detected: family {} revoked", token.familyId());
    }
}
