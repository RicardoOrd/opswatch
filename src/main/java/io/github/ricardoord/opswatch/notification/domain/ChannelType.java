package io.github.ricardoord.opswatch.notification.domain;

/** Where a channel sends. Stored as it is in {@code notification_channels.type}; it never changes. */
public enum ChannelType {
    /** To up to {@code opswatch.limits.recipients-per-channel} addresses. */
    EMAIL,
    /** A signed {@code POST} to an {@code https} URL (OW-043). */
    WEBHOOK
}
