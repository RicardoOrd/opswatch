package io.github.ricardoord.opswatch.monitoring.engine;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the monitoring engine (docs/devops/environments.md#motor-de-monitoreo).
 *
 * @param maxConcurrentChecks checks in flight at once, and connections of the HTTP client
 * @param maxRedirects followed in one check; one more is {@code TOO_MANY_REDIRECTS}
 * @param deadlineGrace margin over the timeout of a monitor before its request is cut
 * @param userAgent sent with every check, so that a target can tell who calls and block it. Its default, with the
 *     version, is in application.yml
 */
@ConfigurationProperties("opswatch.monitoring.engine")
public record MonitoringEngineProperties(
        @DefaultValue("200") int maxConcurrentChecks,
        @DefaultValue("5") int maxRedirects,
        @DefaultValue("200ms") Duration deadlineGrace,
        String userAgent) {

    public MonitoringEngineProperties {
        Objects.requireNonNull(userAgent, "opswatch.monitoring.engine.user-agent is required");
        if (maxConcurrentChecks < 1) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.max-concurrent-checks must be at least 1");
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.max-redirects must not be negative");
        }
        if (deadlineGrace.isNegative()) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.deadline-grace must not be negative");
        }
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.user-agent must not be blank");
        }
    }
}
