package io.github.ricardoord.opswatch.monitoring.engine;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the monitoring engine (docs/devops/environments.md#motor-de-monitoreo).
 *
 * @param enabled whether this instance runs checks; false for one that only serves the API, and in the {@code test}
 *     profile. {@link CheckDispatcher} reads it as a condition
 * @param dispatchInterval between two claims; {@link CheckDispatcher} reads it from its {@code @Scheduled}
 * @param maxConcurrentChecks checks in flight at once, and connections of the HTTP client
 * @param maxBatchSize monitors claimed at most in one dispatch, even with more permits free
 * @param maxRedirects followed in one check; one more is {@code TOO_MANY_REDIRECTS}
 * @param deadlineGrace margin over the timeout of a monitor before its request is cut
 * @param shutdownGrace on shutdown, waited for the checks in flight on top of the longest of their deadlines
 * @param userAgent sent with every check, so that a target can tell who calls and block it. Its default, with the
 *     version, is in application.yml
 */
@ConfigurationProperties("opswatch.monitoring.engine")
public record MonitoringEngineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration dispatchInterval,
        @DefaultValue("200") int maxConcurrentChecks,
        @DefaultValue("500") int maxBatchSize,
        @DefaultValue("5") int maxRedirects,
        @DefaultValue("200ms") Duration deadlineGrace,
        @DefaultValue("5s") Duration shutdownGrace,
        String userAgent) {

    public MonitoringEngineProperties {
        Objects.requireNonNull(userAgent, "opswatch.monitoring.engine.user-agent is required");
        if (dispatchInterval.isNegative() || dispatchInterval.isZero()) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.dispatch-interval must be positive");
        }
        if (maxConcurrentChecks < 1) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.max-concurrent-checks must be at least 1");
        }
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.max-batch-size must be at least 1");
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.max-redirects must not be negative");
        }
        if (deadlineGrace.isNegative()) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.deadline-grace must not be negative");
        }
        if (shutdownGrace.isNegative()) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.shutdown-grace must not be negative");
        }
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("opswatch.monitoring.engine.user-agent must not be blank");
        }
    }
}
