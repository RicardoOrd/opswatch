package io.github.ricardoord.opswatch.notification.application;

import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import java.util.List;

/** Where a channel sends, as a request gives it: the part of its configuration that people choose. */
public sealed interface ChannelDestination {

    ChannelType type();

    /** @param recipients as typed: the service normalizes them and rejects repetitions */
    record Email(List<String> recipients) implements ChannelDestination {

        public Email {
            recipients = List.copyOf(recipients);
        }

        @Override
        public ChannelType type() {
            return ChannelType.EMAIL;
        }
    }

    /** @param url as typed: the service checks it with {@code TargetPolicy} */
    record Webhook(String url) implements ChannelDestination {

        @Override
        public ChannelType type() {
            return ChannelType.WEBHOOK;
        }

        @Override
        public String toString() {
            return "Webhook[…]";
        }
    }
}
