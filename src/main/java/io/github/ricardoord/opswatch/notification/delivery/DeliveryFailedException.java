package io.github.ricardoord.opswatch.notification.delivery;

import org.jspecify.annotations.Nullable;

/**
 * An attempt to send that failed on the side of the receiver or of the way to it: an SMTP server down, slow or that
 * rejects the message. The delivery is tried again with backoff.
 */
public class DeliveryFailedException extends RuntimeException {

    /** {@code notification_deliveries.last_error} holds at most this. */
    public static final int MAX_REASON_LENGTH = 255;

    /**
     * @param reason stored in {@code last_error} and shown in the API: never a recipient, a URL or anything else of the
     *     configuration of the channel, which the API only shows masked
     */
    public DeliveryFailedException(String reason, @Nullable Throwable cause) {
        super(reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason, cause);
    }

    public String reason() {
        return getMessage();
    }
}
