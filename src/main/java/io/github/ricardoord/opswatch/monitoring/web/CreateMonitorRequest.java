package io.github.ricardoord.opswatch.monitoring.web;

import io.github.ricardoord.opswatch.monitoring.application.SettingsChanges;
import io.github.ricardoord.opswatch.monitoring.domain.Monitor;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.domain.ProbeMethod;
import io.github.ricardoord.opswatch.shared.text.VisibleText;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/projects/{projectId}/monitors}. Only {@code name} and {@code url} are required: any other
 * field left out or null takes its default ({@link MonitorSettings#DEFAULTS}). The project comes from the path and the
 * organization from the project: a body that names either is rejected as an unknown property.
 */
public record CreateMonitorRequest(
        @NotBlank
        @Size(max = Monitor.NAME_MAX_LENGTH)
        @Pattern(regexp = VisibleText.PATTERN, message = VisibleText.MESSAGE)
        @Nullable
        String name,

        @Schema(description = "http or https. Its length and host are checked by the SSRF policy (422)")
        @NotBlank
        @Nullable
        String url,

        @Schema(defaultValue = "GET") @Nullable ProbeMethod httpMethod,

        @Schema(description = "Both ends included. By default 200 to 299") @Valid @Nullable
        ExpectedStatusInput expectedStatus,

        @Schema(defaultValue = "60")
        @Min(MonitorSettings.MIN_INTERVAL_SECONDS)
        @Max(MonitorSettings.MAX_INTERVAL_SECONDS)
        @Nullable
        Integer intervalSeconds,

        @Schema(defaultValue = "10000", description = "Less than intervalSeconds, in milliseconds")
        @Min(MonitorSettings.MIN_TIMEOUT_MS)
        @Max(MonitorSettings.MAX_TIMEOUT_MS)
        @Nullable
        Integer timeoutMs,

        @Schema(description = "Up to timeoutMs. A correct answer slower than this is DEGRADED. None by default")
        @Min(1)
        @Max(MonitorSettings.MAX_TIMEOUT_MS)
        @Nullable
        Integer degradedThresholdMs,

        @Schema(defaultValue = "true") @Nullable Boolean followRedirects,

        @Schema(defaultValue = "3", description = "Failed checks in a row that make it DOWN")
        @Min(MonitorSettings.MIN_THRESHOLD)
        @Max(MonitorSettings.MAX_THRESHOLD)
        @Nullable
        Integer failureThreshold,

        @Schema(defaultValue = "2", description = "Successful checks in a row that bring it back from DOWN")
        @Min(MonitorSettings.MIN_THRESHOLD)
        @Max(MonitorSettings.MAX_THRESHOLD)
        @Nullable
        Integer recoveryThreshold) {

    /** Surrounding spaces are a typing slip, not part of the name. */
    public CreateMonitorRequest {
        name = name == null ? null : name.strip();
    }

    SettingsChanges settings() {
        return new SettingsChanges(
                httpMethod,
                expectedStatus == null ? null : expectedStatus.min(),
                expectedStatus == null ? null : expectedStatus.max(),
                intervalSeconds,
                timeoutMs,
                // Null is also the default: none
                PatchField.of(degradedThresholdMs),
                followRedirects,
                failureThreshold,
                recoveryThreshold);
    }
}
