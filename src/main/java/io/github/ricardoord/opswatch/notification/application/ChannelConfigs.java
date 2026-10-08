package io.github.ricardoord.opswatch.notification.application;

import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannel;
import io.github.ricardoord.opswatch.shared.crypto.SecretCipher;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The configuration of a channel, encrypted at rest as JSON with {@code notification_channels.config:<channelId>} as
 * associated data (docs/security/security-architecture.md#cifrado-de-datos-sensibles-en-la-base-de-datos). The purpose
 * differs from that of the headers of a monitor, so a ciphertext cannot be moved from one table to the other, and the id
 * keeps it from being moved between channels.
 */
@Component
public class ChannelConfigs {

    static final String PURPOSE = "notification_channels.config:";

    /** Its own mapper: what is sealed must not change with the settings of the web layer. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SecretCipher cipher;

    ChannelConfigs(SecretCipher cipher) {
        this.cipher = cipher;
    }

    byte[] seal(UUID channelId, ChannelConfig config) {
        return cipher.encrypt(JSON.writeValueAsBytes(config), PURPOSE + channelId);
    }

    /**
     * For the API and, from OW-036, for the deliveries.
     *
     * @throws io.github.ricardoord.opswatch.shared.crypto.DecryptionFailedException if it was not sealed for this channel,
     *     or with a key no longer configured
     */
    public ChannelConfig unseal(NotificationChannel channel) {
        byte[] clear = cipher.decrypt(channel.configCiphertext(), PURPOSE + channel.id());
        Class<? extends ChannelConfig> type =
                channel.type() == ChannelType.EMAIL ? ChannelConfig.Email.class : ChannelConfig.Webhook.class;
        return JSON.readValue(clear, type);
    }
}
