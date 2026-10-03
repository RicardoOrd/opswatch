package io.github.ricardoord.opswatch.monitoring.web;

import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.shared.web.NotNullIfPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code expectedStatus} in a request: the range of HTTP statuses that count as correct, both ends included. An absent
 * end keeps its value, or takes the default on creation; a null one is rejected.
 */
public record ExpectedStatusInput(
        @JsonDeserialize(using = NotNullIfPresent.class)
        @Min(MonitorSettings.MIN_STATUS)
        @Max(MonitorSettings.MAX_STATUS)
        @Nullable
        Integer min,

        @JsonDeserialize(using = NotNullIfPresent.class)
        @Min(MonitorSettings.MIN_STATUS)
        @Max(MonitorSettings.MAX_STATUS)
        @Nullable
        Integer max) {}
