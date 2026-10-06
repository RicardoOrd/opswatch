package io.github.ricardoord.opswatch.notification.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where to send the notifications of the incidents of an organization, or of one of its projects
 * (docs/architecture/domain-model.md#notificationchannel). People edit it, with optimistic locking. Its configuration
 * (recipients, or URL and signing secret) is only ever stored encrypted: the service seals it, because the id of the
 * channel is its associated data and a converter would not know it on reading.
 */
@Entity
@Table(name = "notification_channels")
public class NotificationChannel {

    public static final int NAME_MAX_LENGTH = 100;

    @Id
    private UUID id;

    private UUID organizationId;

    /** Null: every project of the organization. */
    private @Nullable UUID projectId;

    private String name;

    @Enumerated(EnumType.STRING)
    private ChannelType type;

    private byte[] configCiphertext;

    private boolean enabled;

    private Instant createdAt;

    private Instant updatedAt;

    /** Null until the first save: with the id assigned up front, this is how Spring Data knows the entity is new. */
    @Version
    private @Nullable Long version;

    /** For JPA, which populates the fields. */
    protected NotificationChannel() {}

    private NotificationChannel(
            UUID id,
            UUID organizationId,
            @Nullable UUID projectId,
            String name,
            ChannelType type,
            byte[] configCiphertext,
            Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.name = name;
        this.type = type;
        this.configCiphertext = configCiphertext.clone();
        this.enabled = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Enabled from the start.
     *
     * @param projectId a project of the organization, already checked; null for every project
     * @param configCiphertext sealed with this {@code id}
     * @throws IllegalArgumentException if the name breaks an invariant (the request validation should have caught it)
     */
    public static NotificationChannel create(
            UUID id,
            UUID organizationId,
            @Nullable UUID projectId,
            String name,
            ChannelType type,
            byte[] configCiphertext,
            Clock clock) {
        return new NotificationChannel(
                id, organizationId, projectId, validName(name), type, configCiphertext, now(clock));
    }

    public void rename(String name, Clock clock) {
        String cleanName = validName(name);
        if (!cleanName.equals(this.name)) {
            this.name = cleanName;
            this.updatedAt = now(clock);
        }
    }

    /** @param projectId a project of the organization, already checked; null for every project */
    public void limitTo(@Nullable UUID projectId, Clock clock) {
        if (!Objects.equals(projectId, this.projectId)) {
            this.projectId = projectId;
            this.updatedAt = now(clock);
        }
    }

    /** A disabled channel keeps its configuration and receives nothing. */
    public void enable(boolean enabled, Clock clock) {
        if (enabled != this.enabled) {
            this.enabled = enabled;
            this.updatedAt = now(clock);
        }
    }

    /**
     * A sealed ciphertext differs on every encryption, so only the service can tell whether the configuration changed:
     * it calls this only when it did.
     *
     * @param configCiphertext sealed with the id of this channel
     */
    public void reconfigure(byte[] configCiphertext, Clock clock) {
        this.configCiphertext = configCiphertext.clone();
        this.updatedAt = now(clock);
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public @Nullable UUID projectId() {
        return projectId;
    }

    public String name() {
        return name;
    }

    public ChannelType type() {
        return type;
    }

    public byte[] configCiphertext() {
        return configCiphertext.clone();
    }

    public boolean enabled() {
        return enabled;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Not {@code version()}: Spring Data reads an accessor of that name to tell whether the entity is new.
     *
     * @return null until the first save
     */
    public @Nullable Long savedVersion() {
        return version;
    }

    /** Surrounding spaces are a typing slip. */
    private static String validName(String name) {
        String clean = name.strip();
        int length = clean.codePointCount(0, clean.length());
        if (length < 1 || length > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("A channel name has from 1 to " + NAME_MAX_LENGTH + " characters");
        }
        return clean;
    }

    private static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof NotificationChannel channel && id.equals(channel.id()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Never the configuration, not even encrypted. */
    @Override
    public String toString() {
        return "NotificationChannel[id=" + id + ", type=" + type + ", enabled=" + enabled + "]";
    }
}
