package io.github.ricardoord.opswatch.notification.application;

import io.github.ricardoord.opswatch.notification.domain.NotificationChannel;

/**
 * A channel with its configuration in clear, for the web layer, which masks it.
 *
 * @param showSecret true only in the response that creates the channel or rotates its secret: the only times the
 *     signing secret leaves
 */
public record ChannelView(NotificationChannel channel, ChannelConfig config, boolean showSecret) {}
