package io.github.ricardoord.opswatch.monitoring.web;

import io.github.ricardoord.opswatch.monitoring.application.SettingsChanges;
import io.github.ricardoord.opswatch.monitoring.domain.Monitor;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.domain.ProbeMethod;
import io.github.ricardoord.opswatch.shared.text.VisibleText;
import io.github.ricardoord.opswatch.shared.web.NotNullIfPresent;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of {@code PATCH /api/v1/monitors/{monitorId}}. An absent field does not change, and only
 * {@code degradedThresholdMs} admits null, which turns the degraded state off. The rules between fields are checked on
 * the result: lowering only {@code intervalSeconds} below {@code timeoutMs} is rejected.
 */
public record UpdateMonitorRequest(
        @JsonDeserialize(using = NotNullIfPresent.class)
        @Size(min = 1, max = Monitor.NAME_MAX_LENGTH)
        @Pattern(regexp = VisibleText.PATTERN, message = VisibleText.MESSAGE)
        @Nullable
        String name,

        @Schema(description = "Checked again by the SSRF policy (422)")
        @JsonDeserialize(using = NotNullIfPresent.class)
        @Size(min = 1)
        @Nullable
        String url,

        @JsonDeserialize(using = NotNullIfPresent.class) @Nullable
        ProbeMethod httpMethod,

        @Schema(description = "An absent end does not change")
        @JsonDeserialize(using = NotNullIfPresent.class)
        @Valid
        @Nullable
        ExpectedStatusInput expectedStatus,

        @JsonDeserialize(using = NotNullIfPresent.class)
        @Min(MonitorSettings.MIN_INTERVAL_SECONDS)
        @Max(MonitorSettings.MAX_INTERVAL_SECONDS)
        @Nullable
        Integer intervalSeconds,

        @JsonDeserialize(using = NotNullIfPresent.class)
        @Min(MonitorSettings.MIN_TIMEOUT_MS)
        @Max(MonitorSettings.MAX_TIMEOUT_MS)
        @Nullable
        Integer timeoutMs,

        @Schema(
                implementation = Integer.class,
                nullable = true,
                minimum = "1",
                maximum = "30000",
                description = "Absent: unchanged. null: no degraded state")
        PatchField<@Min(1) @Max(MonitorSettings.MAX_TIMEOUT_MS) Integer> degradedThresholdMs,

        @JsonDeserialize(using = NotNullIfPresent.class) @Nullable
        Boolean followRedirects,

        @JsonDeserialize(using = NotNullIfPresent.class)
        @Min(MonitorSettings.MIN_THRESHOLD)
        @Max(MonitorSettings.MAX_THRESHOLD)
        @Nullable
        Integer failureThreshold,

        @JsonDeserialize(using = NotNullIfPresent.class)
        @Min(MonitorSettings.MIN_THRESHOLD)
        @Max(MonitorSettings.MAX_THRESHOLD)
        @Nullable
        Integer recoveryThreshold) {

    /** Surrounding spaces are a typing slip, not part of the name. */
    public UpdateMonitorRequest {
        name = name == null ? null : name.strip();
        degradedThresholdMs = degradedThresholdMs == null ? PatchField.absent() : degradedThresholdMs;
    }

    SettingsChanges settings() {
        return new SettingsChanges(
                httpMethod,
                expectedStatus == null ? null : expectedStatus.min(),
                expectedStatus == null ? null : expectedStatus.max(),
                intervalSeconds,
                timeoutMs,
                degradedThresholdMs,
                followRedirects,
                failureThreshold,
                recoveryThreshold);
    }
}
