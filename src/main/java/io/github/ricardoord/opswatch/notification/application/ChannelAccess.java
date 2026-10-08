package io.github.ricardoord.opswatch.notification.application;

import io.github.ricardoord.opswatch.notification.domain.NotificationChannel;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannelRepository;
import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A channel is authorized through its organization. Someone who is not a member gets the 404 of a missing channel,
 * never one that names its organization.
 */
@Component
class ChannelAccess {

    private final NotificationChannelRepository channels;
    private final AccessControl access;

    ChannelAccess(NotificationChannelRepository channels, AccessControl access) {
        this.channels = channels;
        this.access = access;
    }

    /**
     * @throws ResourceNotFoundException if it is missing, or the user is not a member of its organization (404)
     * @throws io.github.ricardoord.opswatch.shared.error.PermissionDeniedException if the role of the user does not
     *     allow it (403)
     */
    NotificationChannel require(UUID userId, UUID channelId, Permission permission) {
        NotificationChannel channel = channels.findById(channelId)
                .orElseThrow(() -> new ResourceNotFoundException("notification channel", channelId));
        try {
            access.require(userId, channel.organizationId(), permission);
        } catch (ResourceNotFoundException ex) {
            throw new ResourceNotFoundException("notification channel", channelId);
        }
        return channel;
    }
}
