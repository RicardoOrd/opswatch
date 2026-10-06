package io.github.ricardoord.opswatch.notification.application;

import java.util.List;

/**
 * The configuration of a channel, in clear: what {@link ChannelConfigs} seals into
 * {@code notification_channels.config_ciphertext}. Its JSON is part of what is stored: renaming a component needs a
 * migration of the ciphertexts.
 */
public sealed interface ChannelConfig {

    /** @param recipients normalized, without repetitions */
    record Email(List<String> recipients) implements ChannelConfig {

        public Email {
            recipients = List.copyOf(recipients);
        }

        @Override
        public String toString() {
            return "Email[" + recipients.size() + " recipients]";
        }
    }

    /**
     * @param url as {@code TargetPolicy} normalized it
     * @param signingSecret the key of the HMAC of every delivery (OW-043): it leaves only in the response that creates
     *     or rotates it
     */
    record Webhook(String url, String signingSecret) implements ChannelConfig {

        /** Never the URL, which can carry a token, or the secret. */
        @Override
        public String toString() {
            return "Webhook[…]";
        }
    }
}
