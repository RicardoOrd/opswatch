package io.github.ricardoord.opswatch.monitoring.web;

import io.github.ricardoord.opswatch.monitoring.domain.Monitor;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorState;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStatus;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorWithState;
import io.github.ricardoord.opswatch.monitoring.domain.ProbeMethod;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A monitor with its current state. {@code url} is the normalized one that was stored; {@code version} is the value
 * of its {@code ETag}, for {@code If-Match}.
 */
public record MonitorResponse(
        UUID id,
        UUID projectId,
        UUID organizationId,
        String name,
        String url,
        ProbeMethod httpMethod,
        ExpectedStatusRange expectedStatus,
        int intervalSeconds,
        int timeoutMs,
        @Nullable Integer degradedThresholdMs,
        boolean followRedirects,
        int failureThreshold,
        int recoveryThreshold,
        CurrentState state,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** Both ends included. */
    public record ExpectedStatusRange(int min, int max) {}

    /**
     * Written by the engine: until it runs (v0.3.0), every monitor is {@code PENDING} with no check.
     *
     * @param nextCheckAt null while paused
     */
    public record CurrentState(
            MonitorStatus status,
            Instant statusChangedAt,
            @Nullable Instant lastCheckedAt,
            @Nullable Integer lastResponseTimeMs,
            @Nullable Integer lastHttpStatus,
            int consecutiveFailures,
            @Nullable Instant nextCheckAt) {

        static CurrentState from(MonitorState state) {
            return new CurrentState(
                    state.status(),
                    state.statusChangedAt(),
                    state.lastCheckedAt(),
                    state.lastResponseTimeMs(),
                    state.lastHttpStatus(),
                    state.consecutiveFailures(),
                    state.nextCheckAt());
        }
    }

    static MonitorResponse from(MonitorWithState monitorWithState) {
        Monitor monitor = monitorWithState.monitor();
        MonitorSettings settings = monitor.settings();
        return new MonitorResponse(
                monitor.id(),
                monitor.projectId(),
                monitor.organizationId(),
                monitor.name(),
                monitor.url().toString(),
                settings.httpMethod(),
                new ExpectedStatusRange(settings.expectedStatusMin(), settings.expectedStatusMax()),
                settings.intervalSeconds(),
                settings.timeoutMs(),
                settings.degradedThresholdMs(),
                settings.followRedirects(),
                settings.failureThreshold(),
                settings.recoveryThreshold(),
                CurrentState.from(monitorWithState.state()),
                monitor.createdAt(),
                monitor.updatedAt(),
                monitor.savedVersion());
    }
}
