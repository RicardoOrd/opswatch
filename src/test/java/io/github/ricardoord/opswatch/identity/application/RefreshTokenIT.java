package io.github.ricardoord.opswatch.identity.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.identity.domain.RevocationReason;
import io.github.ricardoord.opswatch.identity.domain.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Rotation under concurrency, revocation of every session and the purge, against PostgreSQL. */
@IntegrationTest
class RefreshTokenIT {

    private static final int REPETITIONS = 50;
    private static final String CLIENT = "203.0.113.7";

    @Autowired
    private RefreshTokenService refreshTokens;

    @Autowired
    private RefreshTokenPurgeJob purgeJob;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The strict behavior of ADR-004: one of the two gets the next token, the other one is treated as reuse, and the
     * session ends for both. Without {@code FOR UPDATE}, both could rotate the same token and two valid tokens of one
     * family would exist.
     */
    @Test
    void ofTwoSimultaneousRefreshesWithTheSameTokenExactlyOneSucceedsAndTheFamilyIsRevoked() throws Exception {
        UUID userId = users.save(aUser().build()).id();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < REPETITIONS; i++) {
                IssuedRefreshToken token = refreshTokens.open(userId);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> outcomes = new ArrayList<>();
                for (int thread = 0; thread < 2; thread++) {
                    outcomes.add(executor.submit(() -> {
                        start.await();
                        try {
                            refreshTokens.rotate(token.value(), CLIENT);
                            return true;
                        } catch (InvalidRefreshTokenException ex) {
                            return false;
                        }
                    }));
                }
                start.countDown();

                List<Boolean> results = new ArrayList<>();
                for (Future<Boolean> outcome : outcomes) {
                    results.add(outcome.get(30, TimeUnit.SECONDS));
                }
                assertThat(results).as("repetition %d", i).containsExactlyInAnyOrder(true, false);
                assertThat(activeTokensInFamilyOf(token.value()))
                        .as("repetition %d", i)
                        .isZero();
            }
        }
    }

    @Test
    void revokingEveryFamilyOfAUserLeavesOtherUsersAlone() {
        UUID userId = users.save(aUser().build()).id();
        UUID otherUserId = users.save(aUser().build()).id();
        IssuedRefreshToken laptop = refreshTokens.open(userId);
        IssuedRefreshToken phone = refreshTokens.open(userId);
        IssuedRefreshToken spent = refreshTokens.open(userId);
        IssuedRefreshToken rotated = refreshTokens.rotate(spent.value(), CLIENT).refreshToken();
        IssuedRefreshToken other = refreshTokens.open(otherUserId);

        int revoked = refreshTokens.revokeAllOf(userId, RevocationReason.PASSWORD_CHANGED);

        assertThat(revoked).isEqualTo(3);
        assertThat(reason(laptop)).isEqualTo("PASSWORD_CHANGED");
        assertThat(reason(phone)).isEqualTo("PASSWORD_CHANGED");
        assertThat(reason(rotated)).isEqualTo("PASSWORD_CHANGED");
        assertThat(reason(spent)).isEqualTo("ROTATED");
        assertThat(reason(other)).isNull();
    }

    @Test
    void thePurgeDeletesTokensSpentBeforeTheGracePeriodAndKeepsTheRest() {
        UUID userId = users.save(aUser().build()).id();
        Instant now = Instant.now();
        Instant longAgo = now.minus(Duration.ofDays(10));
        Instant recently = now.minus(Duration.ofDays(1));
        Instant later = now.plus(Duration.ofDays(5));

        UUID expiredLongAgo = insert(userId, longAgo, null, null);
        UUID loggedOutLongAgo = insert(userId, later, longAgo, RevocationReason.LOGOUT);
        UUID rotatedLongAgoButNotExpired = insert(userId, later, longAgo, RevocationReason.ROTATED);
        UUID expiredRecently = insert(userId, recently, null, null);
        UUID loggedOutRecently = insert(userId, later, recently, RevocationReason.LOGOUT);
        UUID active = insert(userId, later, null, null);

        assertThat(purgeJob.purge()).isGreaterThanOrEqualTo(2);

        assertThat(existing(
                        expiredLongAgo,
                        loggedOutLongAgo,
                        rotatedLongAgoButNotExpired,
                        expiredRecently,
                        loggedOutRecently,
                        active))
                .containsExactlyInAnyOrder(rotatedLongAgoButNotExpired, expiredRecently, loggedOutRecently, active);
    }

    private int activeTokensInFamilyOf(String value) {
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM refresh_tokens
                WHERE family_id = (SELECT family_id FROM refresh_tokens WHERE token_hash = ?)
                  AND revoked_at IS NULL
                """, Integer.class, (Object) RefreshTokenService.hash(value));
        return active == null ? 0 : active;
    }

    private @Nullable String reason(IssuedRefreshToken token) {
        return jdbc.queryForObject(
                "SELECT revocation_reason FROM refresh_tokens WHERE token_hash = ?", String.class, (Object)
                        RefreshTokenService.hash(token.value()));
    }

    private UUID insert(
            UUID userId, Instant expiresAt, @Nullable Instant revokedAt, @Nullable RevocationReason reason) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, issued_at, expires_at,
                                            family_expires_at, revoked_at, revocation_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                userId,
                id,
                RefreshTokenService.hash(id.toString()),
                utc(expiresAt.minus(Duration.ofDays(14))),
                utc(expiresAt),
                utc(expiresAt),
                revokedAt == null ? null : utc(revokedAt),
                reason == null ? null : reason.name());
        return id;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private List<UUID> existing(UUID... ids) {
        String placeholders = String.join(", ", Collections.nCopies(ids.length, "?"));
        return jdbc.queryForList(
                "SELECT id FROM refresh_tokens WHERE id IN (" + placeholders + ")", UUID.class, (Object[]) ids);
    }
}
