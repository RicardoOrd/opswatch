package io.github.ricardoord.opswatch.identity.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.shared.error.RateLimitExceededException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rate limits of the authentication endpoints (docs/security/security-architecture.md#8-rate-limiting), checked before
 * any other work. In memory: exact with one instance, multiplied by the number of instances with more (ADR-009).
 *
 * <p>Nothing gets locked: a request over the limit is rejected until the bucket refills, so an attacker cannot keep
 * the owner of an account out any longer than the attack itself.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class AuthRateLimiter {

    /**
     * Keys each limit remembers at most. An entry takes a few hundred bytes, so the limits stay within a few MB however
     * many addresses or emails an attacker sends. A full cache keeps the keys used most, which are the ones under
     * attack.
     */
    static final int MAX_KEYS_PER_LIMIT = 10_000;

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);

    private final Limit loginPerIp;
    private final Limit loginPerEmail;
    private final Limit registerPerIp;
    private final Limit refreshPerIp;
    private final Limit passwordChangePerUser;

    public AuthRateLimiter(RateLimitProperties properties, Clock clock) {
        TimeMeter time = new ClockTimeMeter(clock);
        this.loginPerIp = new Limit("login-per-ip", properties.loginPerIp(), time);
        this.loginPerEmail = new Limit("login-per-email", properties.loginPerEmail(), time);
        this.registerPerIp = new Limit("register-per-ip", properties.registerPerIp(), time);
        this.refreshPerIp = new Limit("refresh-per-ip", properties.refreshPerIp(), time);
        this.passwordChangePerUser = new Limit("password-change-per-user", properties.passwordChangePerUser(), time);
    }

    /**
     * Per client address first and then per account, whatever the case of the email. An attempt the address limit
     * rejects does not count against the account.
     *
     * @param clientAddress as the server sees it: {@code X-Forwarded-For} only counts when it comes from the proxy
     * @throws RateLimitExceededException with the time until the next attempt is allowed
     */
    public void checkLogin(String clientAddress, String email) {
        loginPerIp.consume(addressKey(clientAddress), clientAddress, UnaryOperator.identity());
        String normalizedEmail = User.normalizeEmail(email);
        loginPerEmail.consume(
                normalizedEmail,
                clientAddress,
                event -> event.addKeyValue("user.email.hash", EmailHash.of(normalizedEmail)));
    }

    /** @throws RateLimitExceededException with the time until the next attempt is allowed */
    public void checkRegistration(String clientAddress) {
        registerPerIp.consume(addressKey(clientAddress), clientAddress, UnaryOperator.identity());
    }

    /** @throws RateLimitExceededException with the time until the next attempt is allowed */
    public void checkRefresh(String clientAddress) {
        refreshPerIp.consume(addressKey(clientAddress), clientAddress, UnaryOperator.identity());
    }

    /**
     * Per user, from any address: a stolen access token must not become unlimited guesses of the current password.
     *
     * @throws RateLimitExceededException with the time until the next attempt is allowed
     */
    public void checkPasswordChange(UUID userId, String clientAddress) {
        passwordChangePerUser.consume(userId.toString(), clientAddress, event -> event.addKeyValue("user.id", userId));
    }

    /**
     * An IPv4 address as is. An IPv6 address by its /64 prefix, the usual allocation to a single customer: otherwise an
     * attacker would get a fresh bucket for each of its 2^64 addresses.
     */
    static String addressKey(String clientAddress) {
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(clientAddress);
        } catch (IllegalArgumentException ex) {
            return clientAddress;
        }
        if (address instanceof Inet6Address) {
            return HexFormat.of().formatHex(address.getAddress(), 0, 8) + "/64";
        }
        return address.getHostAddress();
    }

    /** One limit: a bucket per key, in a cache bounded in size and in time. */
    private static final class Limit {

        private final String name;
        private final Bandwidth bandwidth;
        private final TimeMeter time;
        private final Cache<String, KeyBucket> buckets;

        Limit(String name, RateLimit limit, TimeMeter time) {
            this.name = name;
            this.bandwidth = Bandwidth.builder()
                    .capacity(limit.capacity())
                    .refillGreedy(limit.capacity(), limit.period())
                    .build();
            this.time = time;
            this.buckets = Caffeine.newBuilder()
                    .maximumSize(MAX_KEYS_PER_LIMIT)
                    // An idle bucket is full again after one period: forgetting it then changes nothing
                    .expireAfterAccess(limit.period())
                    .ticker(time::currentTimeNanos)
                    .build();
        }

        /** @param subject adds the account the key names, if any, to the security event (the email only as a hash) */
        void consume(String key, String clientAddress, UnaryOperator<LoggingEventBuilder> subject) {
            KeyBucket bucket = buckets.get(key, unused -> new KeyBucket(bandwidth, time));
            ConsumptionProbe probe = bucket.tokens.tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                bucket.rejecting.set(false);
                return;
            }
            // One security event when a key hits the limit, not one per rejected request: a flood of requests must
            // not become a flood of log lines
            if (bucket.rejecting.compareAndSet(false, true)) {
                logLimitReached(clientAddress, subject);
            }
            throw new RateLimitExceededException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }

        /** A security event (docs/devops/observability.md#eventos-de-seguridad). */
        private void logLimitReached(String clientAddress, UnaryOperator<LoggingEventBuilder> subject) {
            LoggingEventBuilder event = log.atWarn()
                    .addKeyValue("event.category", "security")
                    .addKeyValue("event.action", "auth.rate_limited")
                    .addKeyValue("event.reason", name)
                    .addKeyValue("client.address", clientAddress);
            subject.apply(event).log("Rate limit {} reached", name);
        }
    }

    private static final class KeyBucket {

        private final Bucket tokens;

        /** Whether the last request was rejected, so the event is logged once per burst. */
        private final AtomicBoolean rejecting = new AtomicBoolean();

        KeyBucket(Bandwidth bandwidth, TimeMeter time) {
            this.tokens = Bucket.builder()
                    .addLimit(bandwidth)
                    .withCustomTimePrecision(time)
                    .build();
        }
    }

    /** Bucket4j and Caffeine read the injected clock, so tests can move time forward. */
    private record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            Instant now = clock.instant();
            return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
