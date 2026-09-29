package io.github.ricardoord.opswatch.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Rotation and family rules, without a database. */
class RefreshTokenTest {

    private static final Instant LOGIN = Instant.parse("2026-09-29T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(14);
    private static final Duration FAMILY_MAX_TTL = Duration.ofDays(30);

    private final UUID userId = UUID.randomUUID();
    private final RefreshToken first =
            RefreshToken.openFamily(UUID.randomUUID(), userId, new byte[] {1}, LOGIN, TTL, FAMILY_MAX_TTL);

    @Test
    void aLoginOpensAFamilyNamedAfterItsFirstToken() {
        assertThat(first.familyId()).isEqualTo(first.id());
        assertThat(first.userId()).isEqualTo(userId);
        assertThat(first.issuedAt()).isEqualTo(LOGIN);
        assertThat(first.expiresAt()).isEqualTo(LOGIN.plus(TTL));
        assertThat(first.familyExpiresAt()).isEqualTo(LOGIN.plus(FAMILY_MAX_TTL));
        assertThat(first.isActive(LOGIN)).isTrue();
        assertThat(first.isNew()).isTrue();
    }

    @Test
    void rotatingSpendsTheTokenOnASuccessorOfTheSameFamily() {
        Instant later = LOGIN.plus(Duration.ofDays(1));
        UUID nextId = UUID.randomUUID();

        RefreshToken next = first.rotate(nextId, new byte[] {2}, later, TTL);

        assertThat(first.isActive(later)).isFalse();
        assertThat(first.wasRotated()).isTrue();
        assertThat(first.revocationReason()).isEqualTo(RevocationReason.ROTATED);
        assertThat(first.revokedAt()).isEqualTo(later);
        assertThat(first.replacedById()).isEqualTo(nextId);

        assertThat(next.id()).isEqualTo(nextId);
        assertThat(next.familyId()).isEqualTo(first.familyId());
        assertThat(next.userId()).isEqualTo(userId);
        assertThat(next.expiresAt()).isEqualTo(later.plus(TTL));
        assertThat(next.familyExpiresAt()).isEqualTo(first.familyExpiresAt());
        assertThat(next.isActive(later)).isTrue();
    }

    @Test
    void noTokenOutlivesItsFamily() {
        Instant nearTheEnd = LOGIN.plus(Duration.ofDays(25));

        RefreshToken next = first.rotate(UUID.randomUUID(), new byte[] {2}, LOGIN.plus(Duration.ofDays(13)), TTL)
                .rotate(UUID.randomUUID(), new byte[] {3}, nearTheEnd, TTL);

        assertThat(next.expiresAt()).isEqualTo(LOGIN.plus(FAMILY_MAX_TTL));
        assertThat(next.isActive(LOGIN.plus(FAMILY_MAX_TTL))).isFalse();
    }

    @Test
    void aFamilyShorterThanTheTokenLifetimeCapsTheFirstToken() {
        RefreshToken token =
                RefreshToken.openFamily(UUID.randomUUID(), userId, new byte[] {1}, LOGIN, TTL, Duration.ofDays(7));

        assertThat(token.expiresAt()).isEqualTo(LOGIN.plus(Duration.ofDays(7)));
    }

    @Test
    void expiresAtItsExpiryInstant() {
        assertThat(first.isActive(first.expiresAt().minusNanos(1000))).isTrue();
        assertThat(first.isActive(first.expiresAt())).isFalse();
    }

    @Test
    void onlyAnActiveTokenRotates() {
        RefreshToken spent =
                RefreshToken.openFamily(UUID.randomUUID(), userId, new byte[] {1}, LOGIN, TTL, FAMILY_MAX_TTL);
        spent.rotate(UUID.randomUUID(), new byte[] {2}, LOGIN, TTL);

        assertThatThrownBy(() -> spent.rotate(UUID.randomUUID(), new byte[] {3}, LOGIN, TTL))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> first.rotate(UUID.randomUUID(), new byte[] {3}, first.expiresAt(), TTL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyARotationCountsAsSpent() {
        ReflectionTestUtils.setField(first, "revokedAt", LOGIN);
        ReflectionTestUtils.setField(first, "revocationReason", RevocationReason.LOGOUT);

        assertThat(first.isActive(LOGIN)).isFalse();
        assertThat(first.wasRotated()).isFalse();
    }

    @Test
    void keepsItsOwnCopyOfTheHash() {
        byte[] hash = {1, 2, 3};
        RefreshToken token = RefreshToken.openFamily(UUID.randomUUID(), userId, hash, LOGIN, TTL, FAMILY_MAX_TTL);

        hash[0] = 9;

        assertThat((byte[]) ReflectionTestUtils.getField(token, "tokenHash")).containsExactly(1, 2, 3);
    }

    @Test
    void neverPrintsTheHash() {
        assertThat(first.toString())
                .doesNotContain("tokenHash")
                .contains(first.id().toString());
    }
}
