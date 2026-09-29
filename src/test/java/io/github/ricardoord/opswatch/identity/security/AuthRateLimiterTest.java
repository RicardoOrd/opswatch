package io.github.ricardoord.opswatch.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.shared.error.RateLimitExceededException;
import io.github.ricardoord.opswatch.shared.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The limiter alone, with the catalog limits. {@code AuthRateLimitIT} checks them through the real server. */
@ExtendWith(OutputCaptureExtension.class)
class AuthRateLimiterTest {

    private static final String ADDRESS = "203.0.113.10";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-29T10:00:00Z"));
    private final AuthRateLimiter limiter = new AuthRateLimiter(
            new RateLimitProperties(
                    RateLimit.valueOf("10/1m"),
                    RateLimit.valueOf("5/1m"),
                    RateLimit.valueOf("5/1h"),
                    RateLimit.valueOf("30/1m"),
                    RateLimit.valueOf("5/15m")),
            clock);

    @Test
    void theEleventhLoginInAMinuteFromOneAddressIsRejectedUntilATokenRefills() {
        for (int i = 0; i < 10; i++) {
            limiter.checkLogin(ADDRESS, "user" + i + "@example.com");
        }

        // A token every 6 s: 10 per minute
        assertRejected(() -> limiter.checkLogin(ADDRESS, "another@example.com"), 6);
        clock.advance(Duration.ofSeconds(6));
        assertAllowed(() -> limiter.checkLogin(ADDRESS, "another@example.com"));
        assertRejected(() -> limiter.checkLogin(ADDRESS, "another@example.com"), 6);
    }

    @Test
    void theSixthLoginInAMinuteAgainstOneEmailIsRejectedFromAnyAddressAndInAnyCase() {
        for (int i = 0; i < 5; i++) {
            limiter.checkLogin("203.0.113." + i, i % 2 == 0 ? "victim@example.com" : "  VICTIM@Example.com ");
        }

        assertRejected(() -> limiter.checkLogin("198.51.100.1", "Victim@example.com"), 12);
    }

    @Test
    void anAttemptRejectedByTheAddressLimitDoesNotCountAgainstTheAccount() {
        for (int i = 0; i < 10; i++) {
            limiter.checkLogin(ADDRESS, "other" + i + "@example.com");
        }
        for (int i = 0; i < 20; i++) {
            assertRejected(() -> limiter.checkLogin(ADDRESS, "victim@example.com"), 6);
        }

        // Otherwise an attacker blocked by address would still lock the victim out
        assertAllowed(() -> limiter.checkLogin("198.51.100.1", "victim@example.com"));
    }

    @Test
    void theSixthRegistrationInAnHourFromOneAddressIsRejected() {
        for (int i = 0; i < 5; i++) {
            limiter.checkRegistration(ADDRESS);
        }

        assertRejected(() -> limiter.checkRegistration(ADDRESS), 720);
        assertAllowed(() -> limiter.checkRegistration("198.51.100.1"));
    }

    @Test
    void theThirtyFirstRefreshInAMinuteFromOneAddressIsRejected() {
        for (int i = 0; i < 30; i++) {
            limiter.checkRefresh(ADDRESS);
        }

        assertRejected(() -> limiter.checkRefresh(ADDRESS), 2);
    }

    @Test
    void theSixthPasswordChangeInFifteenMinutesForOneUserIsRejectedFromAnyAddress() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            limiter.checkPasswordChange(user, "203.0.113." + i);
        }

        // A token every 3 minutes
        assertRejected(() -> limiter.checkPasswordChange(user, "198.51.100.1"), 180);
        assertAllowed(() -> limiter.checkPasswordChange(UUID.randomUUID(), ADDRESS));
    }

    @Test
    void theLimitsAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.checkRegistration(ADDRESS);
        }

        assertAllowed(() -> limiter.checkLogin(ADDRESS, "ana@example.com"));
        assertAllowed(() -> limiter.checkRefresh(ADDRESS));
    }

    @Test
    void anIpv6ClientIsLimitedByItsSlash64() {
        for (int i = 1; i <= 10; i++) {
            limiter.checkLogin("2001:db8:1:2::" + Integer.toHexString(i), "user" + i + "@example.com");
        }

        assertRejected(() -> limiter.checkLogin("2001:db8:1:2:ffff:ffff:ffff:ffff", "user@example.com"), 6);
        assertAllowed(() -> limiter.checkLogin("2001:db8:1:3::1", "user@example.com"));
    }

    @ParameterizedTest
    @CsvSource({
        "203.0.113.10,                   203.0.113.10",
        "::ffff:203.0.113.10,            203.0.113.10",
        "2001:db8:1:2::1,                20010db800010002/64",
        "2001:0db8:0001:0002:aaaa::ffff, 20010db800010002/64",
        "0:0:0:0:0:0:0:1,                0000000000000000/64",
        "not-an-address,                 not-an-address"
    })
    void keysAddresses(String address, String key) {
        assertThat(AuthRateLimiter.addressKey(address)).isEqualTo(key);
    }

    @Test
    void logsOneSecurityEventPerBurstWithoutTheEmail(CapturedOutput output) {
        for (int i = 0; i < 5; i++) {
            limiter.checkLogin("203.0.113." + i, "victim@example.com");
        }
        for (int i = 0; i < 3; i++) {
            assertRejected(() -> limiter.checkLogin("198.51.100.1", "victim@example.com"), 12);
        }

        assertThat(output.getAll().lines().filter(line -> line.contains("Rate limit login-per-email reached")))
                .hasSize(1);
        assertThat(output).doesNotContain("victim@example.com");

        clock.advance(Duration.ofSeconds(12));
        assertAllowed(() -> limiter.checkLogin("198.51.100.1", "victim@example.com"));
        assertRejected(() -> limiter.checkLogin("198.51.100.1", "victim@example.com"), 12);

        assertThat(output.getAll().lines().filter(line -> line.contains("Rate limit login-per-email reached")))
                .hasSize(2);
    }

    @Test
    void forgetsIdleKeysOnlyOnceTheirBucketIsFullAgain() {
        for (int i = 0; i < 5; i++) {
            limiter.checkRegistration(ADDRESS);
        }

        // A token every 12 minutes: after 59 the bucket holds 4 and is 1 minute away from the fifth. A bucket forgotten
        // too early would come back full, with 5
        clock.advance(Duration.ofMinutes(59));
        for (int i = 0; i < 4; i++) {
            limiter.checkRegistration(ADDRESS);
        }
        assertRejected(() -> limiter.checkRegistration(ADDRESS), 60);
    }

    private static void assertRejected(Executable check, long retryAfterSeconds) {
        assertThatThrownBy(check::execute)
                .isInstanceOfSatisfying(
                        RateLimitExceededException.class,
                        ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(retryAfterSeconds));
    }

    private static void assertAllowed(Executable check) {
        assertThatCode(check::execute).doesNotThrowAnyException();
    }
}
