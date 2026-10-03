package io.github.ricardoord.opswatch.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.domain.ProbeMethod;
import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SettingsChangesTest {

    private static final MonitorSettings CURRENT =
            new MonitorSettings(ProbeMethod.HEAD, 200, 204, 120, 30_000, 2000, false, 5, 4);

    @Test
    void nothingSentChangesNothing() {
        assertThat(changes(null, null, PatchField.absent()).applyTo(CURRENT)).isEqualTo(CURRENT);
    }

    @Test
    void changesOnlyWhatIsSent() {
        MonitorSettings result = changes(300, null, PatchField.absent()).applyTo(CURRENT);

        assertThat(result).isEqualTo(new MonitorSettings(ProbeMethod.HEAD, 200, 204, 300, 30_000, 2000, false, 5, 4));
    }

    @Test
    void aNullDegradedThresholdTurnsItOff() {
        assertThat(changes(null, null, PatchField.of(null)).applyTo(CURRENT).degradedThresholdMs())
                .isNull();
        assertThat(changes(null, null, PatchField.of(5000)).applyTo(CURRENT).degradedThresholdMs())
                .isEqualTo(5000);
    }

    /** The rules between fields hold on the result: each change alone is in its range. */
    @Test
    void checksTheResultNotJustWhatIsSent() {
        assertThatThrownBy(() -> changes(30, null, PatchField.absent()).applyTo(CURRENT))
                .isInstanceOf(InvalidFieldException.class)
                .hasFieldOrPropertyWithValue("field", "timeoutMs");
        assertThatThrownBy(() -> changes(null, 1500, PatchField.absent()).applyTo(CURRENT))
                .isInstanceOf(InvalidFieldException.class)
                .hasFieldOrPropertyWithValue("field", "degradedThresholdMs");
        assertThatThrownBy(() -> new SettingsChanges(null, 205, null, null, null, PatchField.absent(), null, null, null)
                        .applyTo(CURRENT))
                .isInstanceOf(InvalidFieldException.class)
                .hasFieldOrPropertyWithValue("field", "expectedStatus");
    }

    @Test
    void onCreationTheDefaultsFillTheRest() {
        MonitorSettings result = changes(null, 5000, PatchField.absent()).applyTo(MonitorSettings.DEFAULTS);

        assertThat(result).isEqualTo(new MonitorSettings(ProbeMethod.GET, 200, 299, 60, 5000, null, true, 3, 2));
    }

    private static SettingsChanges changes(
            @Nullable Integer intervalSeconds, @Nullable Integer timeoutMs, PatchField<Integer> degraded) {
        return new SettingsChanges(null, null, null, intervalSeconds, timeoutMs, degraded, null, null, null);
    }
}
