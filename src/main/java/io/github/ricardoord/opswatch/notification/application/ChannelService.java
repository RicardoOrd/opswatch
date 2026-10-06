package io.github.ricardoord.opswatch.notification.application;

import io.github.ricardoord.opswatch.egress.TargetKind;
import io.github.ricardoord.opswatch.egress.TargetPolicy;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannel;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannelRepository;
import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.organization.ProjectDirectory;
import io.github.ricardoord.opswatch.organization.ProjectRef;
import io.github.ricardoord.opswatch.shared.error.ConflictException;
import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import io.github.ricardoord.opswatch.shared.error.PreconditionFailedException;
import io.github.ricardoord.opswatch.shared.error.QuotaExceededException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import io.github.ricardoord.opswatch.shared.error.TargetNotAllowedException;
import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import io.github.ricardoord.opswatch.shared.lock.AdvisoryLocks;
import io.github.ricardoord.opswatch.shared.lock.LockSpace;
import io.github.ricardoord.opswatch.shared.web.ETags;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Notification channels: where the incidents of an organization, or of one of its projects, are announced
 * (docs/architecture/domain-model.md#notificationchannel). Authorized through the organization of the channel; a
 * non-member gets the 404 of a missing channel, never one that names its organization.
 *
 * <p>A webhook URL is checked with {@link TargetPolicy}, which resolves its host: always before the transaction, never
 * inside it. The signing secret is generated here and leaves only in the response that creates or rotates it.
 */
@Service
@EnableConfigurationProperties(NotificationLimits.class)
public class ChannelService {

    static final String RECIPIENTS = "email.recipients";

    private final NotificationChannelRepository channels;
    private final ChannelConfigs configs;
    private final SigningSecrets secrets;
    private final AccessControl access;
    private final ProjectDirectory projects;
    private final TargetPolicy targets;
    private final AdvisoryLocks locks;
    private final NotificationLimits limits;
    private final IdGenerator ids;
    private final TransactionOperations transactions;
    private final Clock clock;

    ChannelService(
            NotificationChannelRepository channels,
            ChannelConfigs configs,
            SigningSecrets secrets,
            AccessControl access,
            ProjectDirectory projects,
            TargetPolicy targets,
            AdvisoryLocks locks,
            NotificationLimits limits,
            IdGenerator ids,
            TransactionOperations transactions,
            Clock clock) {
        this.channels = channels;
        this.configs = configs;
        this.secrets = secrets;
        this.access = access;
        this.projects = projects;
        this.targets = targets;
        this.locks = locks;
        this.limits = limits;
        this.ids = ids;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Enabled from the start. Creations in one organization take turns on an advisory lock for the quota, and a channel
     * limited to a project holds it ({@link ProjectDirectory#lockActive}), so that it is never born in a project whose
     * deletion has gone through.
     *
     * @param projectId a project of the organization; null for every project
     * @throws ResourceNotFoundException if the organization or the project is missing or deleted, the project is of
     *     another organization, or the user is not a member (404)
     * @throws InvalidFieldException if the recipients repeat an address or are too many (400)
     * @throws TargetNotAllowedException if {@link TargetPolicy} rejects the URL of a webhook, also for not being
     *     {@code https} (422)
     * @throws QuotaExceededException if the organization has as many channels as allowed (422)
     */
    public ChannelView create(
            UUID userId, UUID organizationId, String name, @Nullable UUID projectId, ChannelDestination destination) {
        access.require(userId, organizationId, Permission.CHANNEL_WRITE);
        ChannelConfig config = configOf(destination);
        return inTransaction(() -> {
            access.require(userId, organizationId, Permission.CHANNEL_WRITE);
            UUID project = checkedProject(organizationId, projectId);
            locks.lock(LockSpace.CHANNELS_OF_ORGANIZATION, organizationId);
            int limit = limits.channelsPerOrganization();
            if (channels.countByOrganizationId(organizationId) >= limit) {
                throw new QuotaExceededException(
                        "The organization already has " + limit + " notification channels, the most allowed.");
            }
            UUID id = ids.next();
            NotificationChannel channel = NotificationChannel.create(
                    id, organizationId, project, name, destination.type(), configs.seal(id, config), clock);
            channels.save(channel);
            // Assigns the version for the ETag of the response
            channels.flush();
            return new ChannelView(channel, config, true);
        });
    }

    /** @throws ResourceNotFoundException if the organization is missing or deleted, or the user is not a member (404) */
    @Transactional(readOnly = true)
    public Page<ChannelView> listOf(UUID userId, UUID organizationId, Pageable pageable) {
        access.require(userId, organizationId, Permission.CHANNEL_READ);
        return channels.findByOrganizationId(organizationId, pageable).map(this::view);
    }

    /** @throws ResourceNotFoundException if it is missing, or the user is not a member of its organization (404) */
    @Transactional(readOnly = true)
    public ChannelView get(UUID userId, UUID channelId) {
        return view(authorized(userId, channelId, Permission.CHANNEL_READ));
    }

    /**
     * Only what is sent changes; the type never does. A new URL keeps the signing secret. The URL is checked before the
     * transaction, which authorizes again and compares {@code If-Match}.
     *
     * @param name null to keep it
     * @param enabled null to keep it
     * @param projectId absent to keep it; null for every project
     * @param destination null to keep it; otherwise of the type of the channel
     * @param ifMatch the {@code If-Match} header, if the client sent one
     * @throws InvalidFieldException if the destination is not of the type of the channel, or its recipients repeat an
     *     address or are too many (400)
     * @throws TargetNotAllowedException if {@link TargetPolicy} rejects the new URL (422)
     * @throws PreconditionFailedException if {@code If-Match} does not match (412)
     */
    public ChannelView update(
            UUID userId,
            UUID channelId,
            @Nullable String name,
            @Nullable Boolean enabled,
            PatchField<UUID> projectId,
            @Nullable ChannelDestination destination,
            @Nullable String ifMatch) {
        NotificationChannel current = authorized(userId, channelId, Permission.CHANNEL_WRITE);
        if (destination != null && destination.type() != current.type()) {
            String field = destination.type() == ChannelType.EMAIL ? "email" : "webhook";
            throw new InvalidFieldException(
                    field, "not-applicable", "does not apply to a " + current.type() + " channel");
        }
        @Nullable ChannelConfig checked = destination == null ? null : configOf(destination);
        return inTransaction(() -> {
            NotificationChannel channel = authorized(userId, channelId, Permission.CHANNEL_WRITE);
            ETags.requireMatch(ifMatch, Objects.requireNonNull(channel.savedVersion()));
            if (name != null) {
                channel.rename(name, clock);
            }
            if (enabled != null) {
                channel.enable(enabled, clock);
            }
            if (projectId.isSent()) {
                channel.limitTo(checkedProject(channel.organizationId(), projectId.value()), clock);
            }
            ChannelConfig config = configs.unseal(channel);
            if (checked != null) {
                // A webhook keeps its secret: only the URL was sent
                ChannelConfig changed = checked instanceof ChannelConfig.Webhook webhook
                        ? new ChannelConfig.Webhook(webhook.url(), ((ChannelConfig.Webhook) config).signingSecret())
                        : checked;
                if (!changed.equals(config)) {
                    channel.reconfigure(configs.seal(channel.id(), changed), clock);
                    config = changed;
                }
            }
            // Fails here on a concurrent change (@Version), and gives the response its new version
            channels.flush();
            return new ChannelView(channel, config, false);
        });
    }

    /**
     * A new signing secret, shown once; the old one stops working at once.
     *
     * @throws ConflictException if it is not a webhook (409)
     */
    public ChannelView rotateSecret(UUID userId, UUID channelId) {
        authorized(userId, channelId, Permission.CHANNEL_WRITE);
        return inTransaction(() -> {
            NotificationChannel channel = authorized(userId, channelId, Permission.CHANNEL_WRITE);
            if (!(configs.unseal(channel) instanceof ChannelConfig.Webhook webhook)) {
                throw new ConflictException("Only a webhook has a signing secret.");
            }
            ChannelConfig rotated = new ChannelConfig.Webhook(webhook.url(), secrets.next());
            channel.reconfigure(configs.seal(channel.id(), rotated), clock);
            channels.flush();
            return new ChannelView(channel, rotated, true);
        });
    }

    /** With its deliveries. */
    @Transactional
    public void delete(UUID userId, UUID channelId) {
        channels.delete(authorized(userId, channelId, Permission.CHANNEL_WRITE));
    }

    /**
     * Recipients normalized as identity normalizes emails, without repetitions; a webhook URL as {@link TargetPolicy}
     * normalizes it, with a new secret.
     */
    private ChannelConfig configOf(ChannelDestination destination) {
        return switch (destination) {
            case ChannelDestination.Email email -> new ChannelConfig.Email(recipients(email.recipients()));
            case ChannelDestination.Webhook webhook ->
                new ChannelConfig.Webhook(
                        targets.validate(webhook.url(), TargetKind.WEBHOOK).toString(), secrets.next());
        };
    }

    private List<String> recipients(List<String> typed) {
        int limit = limits.recipientsPerChannel();
        if (typed.size() > limit) {
            throw new InvalidFieldException(RECIPIENTS, "too-many", "must have at most " + limit + " addresses");
        }
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String recipient : typed) {
            String address = recipient.strip().toLowerCase(Locale.ROOT);
            if (!seen.add(address)) {
                throw new InvalidFieldException(RECIPIENTS, "duplicate", "must not repeat an address");
            }
            normalized.add(address);
        }
        return normalized;
    }

    /**
     * Held until the transaction ends, so that a project being deleted waits for the channel, and its deletion deletes
     * it too.
     *
     * @return null for every project
     * @throws ResourceNotFoundException if the project is missing, deleted or of another organization (404)
     */
    private @Nullable UUID checkedProject(UUID organizationId, @Nullable UUID projectId) {
        if (projectId == null) {
            return null;
        }
        ProjectRef project = projects.lockActive(projectId);
        if (!project.organizationId().equals(organizationId)) {
            throw new ResourceNotFoundException("project", projectId);
        }
        return project.id();
    }

    private NotificationChannel authorized(UUID userId, UUID channelId, Permission permission) {
        NotificationChannel channel = channels.findById(channelId)
                .orElseThrow(() -> new ResourceNotFoundException("notification channel", channelId));
        try {
            access.require(userId, channel.organizationId(), permission);
        } catch (ResourceNotFoundException ex) {
            throw new ResourceNotFoundException("notification channel", channelId);
        }
        return channel;
    }

    private ChannelView view(NotificationChannel channel) {
        return new ChannelView(channel, configs.unseal(channel), false);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(transaction -> work.get()));
    }
}
