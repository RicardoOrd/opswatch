package io.github.ricardoord.opswatch.notification.delivery;

import io.github.ricardoord.opswatch.incident.IncidentDirectory;
import io.github.ricardoord.opswatch.incident.IncidentSummary;
import io.github.ricardoord.opswatch.notification.application.ChannelConfigs;
import io.github.ricardoord.opswatch.notification.application.DeliveryProperties;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import io.github.ricardoord.opswatch.notification.domain.ClaimedDelivery;
import io.github.ricardoord.opswatch.notification.domain.DeliveryQueue;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannel;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannelRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Sends the deliveries that are due, in every instance (docs/architecture/events.md#7-flujo-de-eventos). On every poll:
 *
 * <ol>
 *   <li>claims up to {@code batch-size} in a short transaction, counting their attempt and setting them aside for
 *       {@code lease} ({@link DeliveryQueue#claim});
 *   <li>sends each on a virtual thread of its own, out of any transaction, and waits for all of them;
 *   <li>records each result in a short transaction of its own: {@code SENT}, the next attempt after its backoff, or
 *       {@code FAILED} after the last one.
 * </ol>
 *
 * <p>Delivery is at least once: if the instance stops between a send and its record, the lease runs out and the
 * delivery is sent again. A disabled channel sends nothing: its delivery is {@code FAILED} at once.
 */
@Component
@ConditionalOnBooleanProperty(name = "opswatch.notification.delivery.enabled", matchIfMissing = true)
class DeliveryWorker {

    /** {@code opswatch_notification_deliveries_total{channel_type, result}} in Prometheus: one per attempt. */
    static final String DELIVERIES = "opswatch.notification.deliveries";

    static final String SENT = "SENT";

    /** The attempt failed and another one follows. */
    static final String RETRY = "RETRY";

    /** No attempt follows: the last one failed, or the channel is disabled. */
    static final String FAILED = "FAILED";

    static final String CHANNEL_DISABLED = "channel disabled";

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryQueue queue;
    private final NotificationChannelRepository channels;
    private final ChannelConfigs configs;
    private final IncidentDirectory incidents;
    private final Map<ChannelType, ChannelSender> senders;
    private final DeliveryProperties properties;
    private final TransactionOperations transactions;
    private final MeterRegistry meters;
    private final Clock clock;

    DeliveryWorker(
            DeliveryQueue queue,
            NotificationChannelRepository channels,
            ChannelConfigs configs,
            IncidentDirectory incidents,
            List<ChannelSender> senders,
            DeliveryProperties properties,
            TransactionOperations transactions,
            MeterRegistry meters,
            Clock clock) {
        this.queue = queue;
        this.channels = channels;
        this.configs = configs;
        this.incidents = incidents;
        this.senders = new EnumMap<>(ChannelType.class);
        for (ChannelSender sender : senders) {
            if (this.senders.put(sender.type(), sender) != null) {
                throw new IllegalStateException("Two senders for " + sender.type() + " channels");
            }
        }
        this.properties = properties;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${opswatch.notification.delivery.poll-interval:5s}")
    void scheduled() {
        try {
            deliver();
        } catch (RuntimeException ex) {
            // The database, most likely: what is due stays due, and the next poll tries again
            log.atError().setCause(ex).log("Could not claim the deliveries that are due");
        }
    }

    /** @return how many deliveries it attempted */
    int deliver() {
        return deliver(clock.instant());
    }

    /** @param now when they are claimed; the tests set it */
    int deliver(Instant now) {
        Instant claimedAt = now.truncatedTo(ChronoUnit.MICROS);
        List<ClaimedDelivery> claimed = Objects.requireNonNull(transactions.execute(transaction ->
                queue.claim(properties.batchSize(), claimedAt, claimedAt.plus(properties.lease()), senders.keySet())));
        if (claimed.isEmpty()) {
            return 0;
        }
        // Closing waits for every send: the next poll never overlaps this one
        try (ExecutorService sends = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("delivery-", 0).factory())) {
            for (ClaimedDelivery delivery : claimed) {
                sends.execute(() -> attempt(delivery));
            }
        }
        return claimed.size();
    }

    private void attempt(ClaimedDelivery delivery) {
        Optional<NotificationChannel> found;
        try {
            found = channels.findById(delivery.channelId());
        } catch (RuntimeException ex) {
            log.atError().setCause(ex).log("Could not read the channel of delivery {}", delivery.id());
            return;
        }
        if (found.isEmpty()) {
            // Deleted since the claim, and its deliveries with it
            return;
        }
        NotificationChannel channel = found.get();
        if (!channel.enabled()) {
            failed(delivery, channel.type(), CHANNEL_DISABLED, true);
            return;
        }
        try {
            ChannelSender sender = Objects.requireNonNull(senders.get(channel.type()));
            sender.send(notice(delivery, channel), configs.unseal(channel));
        } catch (DeliveryFailedException ex) {
            failed(delivery, channel.type(), ex.reason(), properties.isLast(delivery.attempt()));
            return;
        } catch (RuntimeException ex) {
            // Ours, not of the receiver: a configuration that does not decrypt, a template that fails
            log.atError()
                    .setCause(ex)
                    .addKeyValue("delivery.id", delivery.id())
                    .log("Delivery {} failed for an error of OpsWatch", delivery.id());
            failed(
                    delivery,
                    channel.type(),
                    "internal error: " + ex.getClass().getSimpleName(),
                    properties.isLast(delivery.attempt()));
            return;
        }
        if (record(() -> queue.recordSent(delivery.id(), delivery.attempt(), clock.instant()))) {
            count(channel.type(), SENT);
        }
    }

    private Notice notice(ClaimedDelivery delivery, NotificationChannel channel) {
        UUID incidentId = delivery.incidentId();
        @Nullable
        IncidentSummary incident = incidentId == null
                ? null
                : incidents
                        .findById(incidentId)
                        .orElseThrow(() -> new IllegalStateException("Incident " + incidentId + " does not exist"));
        return new Notice(delivery.id(), delivery.eventType(), delivery.createdAt(), channel.name(), incident);
    }

    private void failed(ClaimedDelivery delivery, ChannelType type, String reason, boolean last) {
        if (last) {
            if (record(() -> queue.recordFailed(delivery.id(), delivery.attempt(), reason))) {
                count(type, FAILED);
                log.atWarn()
                        .addKeyValue("delivery.id", delivery.id())
                        .addKeyValue("channel.id", delivery.channelId())
                        .log("Delivery {} failed after {} attempts: {}", delivery.id(), delivery.attempt(), reason);
            }
            return;
        }
        Instant retryAt = clock.instant().plus(properties.waitAfter(delivery.attempt()));
        if (record(() -> queue.recordRetry(delivery.id(), delivery.attempt(), reason, retryAt))) {
            count(type, RETRY);
            log.atWarn()
                    .addKeyValue("delivery.id", delivery.id())
                    .log(
                            "Attempt {} of delivery {} failed, next at {}: {}",
                            delivery.attempt(),
                            delivery.id(),
                            retryAt,
                            reason);
        }
    }

    /**
     * In a short transaction of its own. A record that finds the delivery moved on (its lease ran out and another
     * worker took it) or gone with its channel changes nothing, and counts nothing.
     */
    private boolean record(BooleanSupplier work) {
        try {
            return Boolean.TRUE.equals(transactions.execute(transaction -> work.getAsBoolean()));
        } catch (RuntimeException ex) {
            // Left as claimed: when the lease runs out it is attempted again
            log.atError().setCause(ex).log("Could not record the result of a delivery");
            return false;
        }
    }

    private void count(ChannelType type, String result) {
        meters.counter(DELIVERIES, "channel_type", type.name(), "result", result)
                .increment();
    }
}
