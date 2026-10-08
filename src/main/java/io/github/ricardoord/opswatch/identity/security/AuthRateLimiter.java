package io.github.ricardoord.opswatch.identity.security;

import io.github.ricardoord.opswatch.identity.domain.User;
import io.github.ricardoord.opswatch.shared.error.RateLimitExceededException;
import io.github.ricardoord.opswatch.shared.ratelimit.KeyedRateLimiter;
import io.github.ricardoord.opswatch.shared.ratelimit.RateLimit;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
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
 * the owner of an account out any longer than the attack itself ({@link KeyedRateLimiter}).
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class AuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);

    private final Limit loginPerIp;
    private final Limit loginPerEmail;
    private final Limit registerPerIp;
    private final Limit refreshPerIp;
    private final Limit passwordChangePerUser;

    public AuthRateLimiter(RateLimitProperties properties, Clock clock) {
        this.loginPerIp = new Limit("login-per-ip", properties.loginPerIp(), clock);
        this.loginPerEmail = new Limit("login-per-email", properties.loginPerEmail(), clock);
        this.registerPerIp = new Limit("register-per-ip", properties.registerPerIp(), clock);
        this.refreshPerIp = new Limit("refresh-per-ip", properties.refreshPerIp(), clock);
        this.passwordChangePerUser = new Limit("password-change-per-user", properties.passwordChangePerUser(), clock);
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

    /** One named limit, with the security event it logs. */
    private static final class Limit {

        private final String name;
        private final KeyedRateLimiter limiter;

        Limit(String name, RateLimit limit, Clock clock) {
            this.name = name;
            this.limiter = new KeyedRateLimiter(limit, clock);
        }

        /** @param subject adds the account the key names, if any, to the security event (the email only as a hash) */
        void consume(String key, String clientAddress, UnaryOperator<LoggingEventBuilder> subject) {
            limiter.consume(key, () -> logLimitReached(clientAddress, subject));
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
}
