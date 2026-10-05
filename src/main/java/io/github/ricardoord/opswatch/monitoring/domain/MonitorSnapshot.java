package io.github.ricardoord.opswatch.monitoring.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * What a check needs of its monitor, read when it was claimed: the request goes out with no transaction open, and the
 * result is judged with the settings it was made with, even if someone changed them meanwhile.
 */
public record MonitorSnapshot(
        UUID monitorId, UUID organizationId, UUID projectId, String name, MonitorSettings settings) {

    public MonitorSnapshot {
        Objects.requireNonNull(monitorId, "monitorId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(settings, "settings");
    }

    public static MonitorSnapshot of(Monitor monitor) {
        return new MonitorSnapshot(
                monitor.id(), monitor.organizationId(), monitor.projectId(), monitor.name(), monitor.settings());
    }
}
