package io.github.ricardoord.opswatch.notification.application;

import io.github.ricardoord.opswatch.notification.domain.DeliveryQueue;
import io.github.ricardoord.opswatch.notification.domain.NotificationDelivery;
import io.github.ricardoord.opswatch.notification.domain.NotificationDeliveryRepository;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.shared.error.RateLimitExceededException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import io.github.ricardoord.opswatch.shared.ratelimit.KeyedRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The deliveries of a channel as people see them: their status and attempts, never their content. And the test of a
 * channel, a {@code TEST} delivery that the same worker sends as any other (OW-036).
 */
@Service
@EnableConfigurationProperties({TestNotificationProperties.class, DeliveryProperties.class})
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final NotificationDeliveryRepository deliveries;
    private final DeliveryQueue queue;
    private final ChannelAccess access;
    private final KeyedRateLimiter testsPerChannel;
    private final DeliveryProperties delivery;
    private final IdGenerator ids;
    private final TransactionOperations transactions;
    private final Clock clock;

    DeliveryService(
            NotificationDeliveryRepository deliveries,
            DeliveryQueue queue,
            ChannelAccess access,
            TestNotificationProperties properties,
            DeliveryProperties delivery,
            IdGenerator ids,
            TransactionOperations transactions,
            Clock clock) {
        this.deliveries = deliveries;
        this.queue = queue;
        this.access = access;
        this.testsPerChannel = new KeyedRateLimiter(properties.rateLimit(), clock);
        this.delivery = delivery;
        this.ids = ids;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Due as any delivery; a disabled channel gets it too, and the worker fails it as {@code channel disabled}. Authorized
     * before the rate limit, so that someone who may not test the channel never uses up its tests.
     *
     * @throws ResourceNotFoundException if it is missing, or the user is not a member of its organization (404)
     * @throws RateLimitExceededException if the channel had as many tests as allowed in the period (429)
     */
    public NotificationDelivery test(UUID userId, UUID channelId) {
        access.require(userId, channelId, Permission.CHANNEL_WRITE);
        testsPerChannel.consume(channelId.toString(), () -> logLimitReached(userId, channelId));
        return Objects.requireNonNull(transactions.execute(transaction -> {
            UUID id = ids.next();
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (!queue.addTest(id, channelId, now, now.plus(delivery.firstWait()))) {
                // Deleted since it was authorized
                throw new ResourceNotFoundException("notification channel", channelId);
            }
            return deliveries.findById(id).orElseThrow();
        }));
    }

    /** @throws ResourceNotFoundException if it is missing, or the user is not a member of its organization (404) */
    @Transactional(readOnly = true)
    public Page<NotificationDelivery> deliveriesOf(UUID userId, UUID channelId, Pageable pageable) {
        access.require(userId, channelId, Permission.CHANNEL_READ);
        return deliveries.findByChannelId(channelId, pageable);
    }

    /** A security event (docs/devops/observability.md#eventos-de-seguridad), once per burst. */
    private static void logLimitReached(UUID userId, UUID channelId) {
        log.atWarn()
                .addKeyValue("event.category", "security")
                .addKeyValue("event.action", "notification.test_rate_limited")
                .addKeyValue("user.id", userId)
                .addKeyValue("channel.id", channelId)
                .log("Rate limit of the tests of channel {} reached", channelId);
    }
}
