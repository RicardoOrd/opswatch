package io.github.ricardoord.opswatch.notification.delivery;

import io.github.ricardoord.opswatch.notification.application.ChannelConfig;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;

/**
 * Sends notices to the channels of one type. {@link DeliveryWorker} only claims the deliveries of the types it has a
 * sender for: those of the others wait in the queue (webhooks, until OW-043).
 */
public interface ChannelSender {

    ChannelType type();

    /**
     * Never inside a transaction: it does I/O with a third party, bounded by timeouts.
     *
     * @param config of a channel of {@link #type()}
     * @throws DeliveryFailedException if this attempt failed and a later one may succeed
     */
    void send(Notice notice, ChannelConfig config);
}
